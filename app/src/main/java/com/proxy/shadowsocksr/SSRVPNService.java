package com.proxy.shadowsocksr;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

import com.proxy.shadowsocksr.impl.SSRLocal;
import com.proxy.shadowsocksr.impl.SSRTunnel;
import com.proxy.shadowsocksr.impl.UDPRelayServer;
import com.proxy.shadowsocksr.impl.interfaces.OnNeedProtectTCPListener;
import com.proxy.shadowsocksr.impl.interfaces.OnNeedProtectUDPListener;
import com.proxy.shadowsocksr.items.ConnectProfile;
import com.proxy.shadowsocksr.util.ConfFileUtil;
import com.proxy.shadowsocksr.util.DNSUtil;
import com.proxy.shadowsocksr.util.InetAddressUtil;
import com.proxy.shadowsocksr.util.ShellUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public final class SSRVPNService extends VpnService implements OnNeedProtectTCPListener,
        OnNeedProtectUDPListener
{
    private final int VPN_MTU = 1500;
    private final String PRIVATE_VLAN = "27.27.27.%s";
    private final String PRIVATE_VLAN6 = "fdfe:dcba:9875::%s";
    private ParcelFileDescriptor conn;

    private ConnectProfile connProfile;

    private volatile boolean isVPNConnected = false;

    private ISSRServiceCallback callback = null;

    private SSRService binder = new SSRService();

    private SSRLocal local = null;
    private SSRTunnel tunnel = null;
    private UDPRelayServer udprs = null;

    @Override public IBinder onBind(Intent intent)
    {
        return binder;
    }

    @Override public void onRevoke()
    {
        stopRunner();
    }

    class SSRService extends ISSRService.Stub
    {
        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException
        {   //if user use system dialog close vpn,onRevoke will not called
            if (code == IBinder.LAST_CALL_TRANSACTION)
            {
                onRevoke();
                if (callback != null)
                {
                    try
                    {
                        callback.onStatusChanged(Consts.STATUS_DISCONNECTED);
                    }
                    catch (RemoteException ignored)
                    {
                    }
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        @Override public boolean status() throws RemoteException
        {
            return isVPNConnected;
        }

        @Override public void registerISSRServiceCallBack(ISSRServiceCallback cb)
        throws RemoteException
        {
            callback = cb;
        }

        @Override public void unRegisterISSRServiceCallBack()
        throws RemoteException
        {
            callback = null;
        }

        @Override public void start(ConnectProfile cp) throws RemoteException
        {
            connProfile = cp;
            if (checkDaemonFile())
            {
                startRunner();
            }
            else
            {
                if (callback != null)
                {
                    try
                    {
                        callback.onStatusChanged(Consts.STATUS_FAILED);
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
        }

        @Override public void stop() throws RemoteException
        {
            stopRunner();
            if (callback != null)
            {
                try
                {
                    callback.onStatusChanged(Consts.STATUS_DISCONNECTED);
                }
                catch (RemoteException ignored)
                {
                }
            }
        }
    }

    @Override public boolean onNeedProtectTCP(Socket socket)
    {
        return protect(socket);
    }

    @Override public boolean onNeedProtectUDP(DatagramSocket udps)
    {
        return protect(udps);
    }

    public boolean checkDaemonFile()
    {
        for (String fn : new String[]{"pdnsd", "tun2socks"})
        {
            File f = new File(Consts.baseDir + fn);
            if (f.exists())
            {
                if (!f.canRead() || !f.canExecute())
                {
                    ShellUtil.runCmd("chmod 755 " + f.getAbsolutePath());
                }
            }
            else
            {
                if (copyDaemonBin(fn, f))
                {
                    ShellUtil.runCmd("chmod 755 " + f.getAbsolutePath());
                    return true;
                }
                return false;
            }
        }
        return true;
    }

    private boolean copyDaemonBin(String file, File out)
    {
        AssetManager am = getAssets();
        String abi = Jni.getABI();
        byte[] buf
                = new byte[1024 *
                           32];//most tf card have 16k or 32k logic unit size, may be 32k buffer is better
        try
        {
            boolean create = out.createNewFile();
            if (!create)
            {
                throw new IOException("Create File Failed!");
            }
            InputStream is = am.open(abi + File.separator + file);
            FileOutputStream fos = new FileOutputStream(out);
            int length = is.read(buf);
            while (length > 0)
            {
                fos.write(buf, 0, length);
                length = is.read(buf);
            }
            fos.flush();
            fos.close();
            is.close();
            return true;
        }
        catch (IOException ignored)
        {
        }
        return false;
    }

    private void startRunner()
    {
        killProcesses();
        //
        new Thread(new Runnable()
        {
            @Override public void run()
            {
                if (!InetAddressUtil.isIPv4Address(connProfile.server) &&
                    !InetAddressUtil.isIPv6Address(connProfile.server))
                {
                    DNSUtil du = new DNSUtil();
                    String ip = du.resolve(connProfile.server, true);
                    if (ip == null)
                    {
                        stopRunner();
                        if (callback != null)
                        {
                            try
                            {
                                callback.onStatusChanged(Consts.STATUS_FAILED);
                            }
                            catch (Exception ignored)
                            {
                            }
                        }
                        return;
                    }
                    connProfile.server = ip;
                }
                //
                startSSRDaemon();
                if (!connProfile.dnsForward)
                {
                    startDnsTunnel();
                    startDnsDaemon();
                }
                //
                int fd = startVpn();
                if (fd != -1)
                {
                    int tries = 1;
                    while (tries < 5)
                    {
                        try
                        {
                            Thread.sleep(1000 * tries);
                        }
                        catch (InterruptedException ignored)
                        {
                        }
                        if (Jni.sendFd(fd) != -1)
                        {
                            isVPNConnected = true;
                            if (callback != null)
                            {
                                try
                                {
                                    callback.onStatusChanged(Consts.STATUS_CONNECTED);
                                }
                                catch (Exception ignored)
                                {
                                }
                            }
                            PendingIntent open = PendingIntent.getActivity(
                                    SSRVPNService.this, -1, new Intent(
                                            SSRVPNService.this, MainActivity.class), 0);
                            NotificationCompat.Builder notificationBuilder
                                    = new NotificationCompat.Builder(SSRVPNService.this);
                            notificationBuilder.setWhen(0)
                                               .setColor(ContextCompat.getColor(
                                                       SSRVPNService.this,
                                                       R.color.material_accent_500))
                                               .setTicker("VPN service started")
                                               .setContentTitle(getString(R.string.app_name))
                                               .setContentText(connProfile.label)
                                               .setContentIntent(open)
                                               .setPriority(NotificationCompat.PRIORITY_MIN)
                                               .setSmallIcon(R.drawable.ic_stat_shadowsocks);
                            startForeground(1, notificationBuilder.build());
                            //
                            return;
                        }
                        tries++;
                    }
                }
                stopRunner();
                if (callback != null)
                {
                    try
                    {
                        callback.onStatusChanged(Consts.STATUS_FAILED);
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
        }).start();
    }

    private void stopRunner()
    {
        isVPNConnected = false;

        //reset
        killProcesses();

        //
        stopForeground(true);

        //close conn
        if (conn != null)
        {
            try
            {
                conn.close();
            }
            catch (IOException ignored)
            {
            }
            conn = null;
        }
        ////////////////
        //stop service
        if (callback == null)
        {
            stopSelf();
        }
    }

    private void startSSRDaemon()
    {
        List<String> aclList = new ArrayList<>();
        if (!connProfile.route.equals("all"))
        {
            switch (connProfile.route)
            {
            case "bypass-lan":
                aclList = Arrays.asList(
                        getResources().getStringArray(R.array.private_route));
                break;
            case "bypass-lan-and-list":
                aclList = Arrays.asList(getResources().getStringArray(R.array.chn_route_full));
                break;
            }
        }

        local = new SSRLocal("127.0.0.1", connProfile.server,
                             connProfile.remotePort,
                             connProfile.localPort,
                             connProfile.passwd,
                             connProfile.cryptMethod,
                             connProfile.tcpProtocol,
                             connProfile.obfsMethod,
                             connProfile.obfsParam,
                             aclList);

        local.setOnNeedProtectTCPListener(this);
        local.start();

//        if (connProfile.dnsForward)
//        {
//            udprs = new UDPRelayServer(connProfile.server, "127.0.0.1", connProfile.remotePort,
//                                       connProfile.localPort, connProfile.cryptMethod,
//                                       connProfile.passwd);
//            udprs.setOnNeedProtectUDPListener(this);
//            udprs.start();
//        }
    }

    private void startDnsTunnel()
    {
        tunnel = new SSRTunnel(connProfile.server, "127.0.0.1", "8.8.8.8", connProfile.remotePort,
                               8163, 53, connProfile.cryptMethod,
                               connProfile.tcpProtocol,
                               connProfile.obfsMethod,
                               connProfile.obfsParam,
                               connProfile.passwd);

        tunnel.setOnNeedProtectTCPListener(this);
        tunnel.start();
    }

    private void startDnsDaemon()
    {
        String pdnsd;
        if (connProfile.route.equals("bypass-lan-and-list"))
        {
            String reject = getResources().getString(R.string.reject);
            String blklst = getResources().getString(R.string.black_list);

            pdnsd = String.format(ConfFileUtil.PdNSdDirect, "0.0.0.0", 8153,
                                  Consts.baseDir + "pdnsd-vpn.pid", reject, blklst, 8163,
                                  connProfile.ipv6Route ? "" : "reject = ::/0;");
        }
        else
        {
            pdnsd = String.format(ConfFileUtil.PdNSdLocal, "0.0.0.0", 8153,
                                  Consts.baseDir + "pdnsd-vpn.pid", 8163,
                                  connProfile.ipv6Route ? "" : "reject = ::/0;");
        }

        ConfFileUtil.writeToFile(pdnsd, new File(Consts.baseDir + "pdnsd-vpn.conf"));

        String cmd = Consts.baseDir + "pdnsd -c " + Consts.baseDir + "pdnsd-vpn.conf";
        ShellUtil.runCmd(cmd);
    }

    private int startVpn()
    {
        Builder builder = new Builder();
        builder.setSession(connProfile.label)
               .setMtu(VPN_MTU)
               .addAddress(String.format(PRIVATE_VLAN, "1"), 24)
               .addDnsServer("8.8.8.8")
               .addDnsServer("8.8.4.4");

        if (connProfile.ipv6Route)
        {
            builder.addAddress(String.format(PRIVATE_VLAN6, "1"), 126);
            builder.addRoute("::", 0);
            //builder.addDnsServer("[2001:4860:4860::8888]");
            //builder.addDnsServer("[2001:4860:4860::8844]");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        {
            if (!connProfile.globalProxy)
            {
                for (String pkg : connProfile.proxyApps)
                {
                    try
                    {
                        builder.addAllowedApplication(pkg);
                    }
                    catch (PackageManager.NameNotFoundException ignored)
                    {
                    }
                }
            }
        }

        if (connProfile.route.equals("all"))
        {
            builder.addRoute("0.0.0.0", 0);
        }
        else
        {
            String[] privateLst = getResources().getStringArray(R.array.bypass_private_route);
            for (String cidr : privateLst)
            {
                String[] sp = cidr.split("/");
                builder.addRoute(sp[0], Integer.valueOf(sp[1]));
            }
        }

        builder.addRoute("8.8.0.0", 16);

        try
        {
            conn = builder.establish();
        }
        catch (Exception e)
        {
            conn = null;
        }

        if (conn == null)
        {
            return -1;
        }

        int fd = conn.getFd();

        String cmd = String.format(Consts.baseDir +
                                   "tun2socks --netif-ipaddr %s"
                                   + " --netif-netmask 255.255.255.0"
                                   + " --socks-server-addr 127.0.0.1:%d"
                                   + " --tunfd %d"
                                   + " --tunmtu %d"
                                   + " --loglevel 0"
                                   + " --pid %stun2socks-vpn.pid",
                                   String.format(PRIVATE_VLAN, "2"),
                                   connProfile.localPort, fd, VPN_MTU, Consts.baseDir);

        if (connProfile.ipv6Route)
        {
            cmd += " --netif-ip6addr " + String.format(PRIVATE_VLAN6, "2");
        }

        if (connProfile.dnsForward)
        {
            cmd += " --enable-udprelay";
        }
        else
        {
            cmd += String.format(" --dnsgw %s:8153", String.format(PRIVATE_VLAN, "1"));
        }

        ShellUtil.runCmd(cmd);

        return fd;
    }

    private void killProcesses()
    {
        if (local != null)
        {
            local.stopSSRLocal();
            local = null;
        }

        if (tunnel != null)
        {
            tunnel.stopTunnel();
            tunnel = null;
        }

        if (udprs != null)
        {
            udprs.stopUDPRelayServer();
            udprs = null;
        }

        final String[] tasks = new String[]{"pdnsd", "tun2socks"};
        List<String> cmds = new ArrayList<>();
        String[] cmdarr;

        for (String task : tasks)
        {
            cmds.add(String.format("chmod 666 %s%s-vpn.pid", Consts.baseDir, task));
        }
        cmdarr = new String[cmds.size()];
        cmds.toArray(cmdarr);
        ShellUtil.runCmd(cmdarr);
        cmds.clear();

        for (String t : tasks)
        {
            try
            {
                File pidf = new File(Consts.baseDir + t + "-vpn.pid");
                int pid = new Scanner(pidf).useDelimiter("\\Z").nextInt();
                android.os.Process.killProcess(pid);
            }
            catch (Exception ignored)
            {
            }
            cmds.add(String.format("rm -f %s%s-vpn.conf", Consts.baseDir, t));
            cmds.add(String.format("rm -f %s%s-vpn.pid", Consts.baseDir, t));
        }
        cmdarr = new String[cmds.size()];
        cmds.toArray(cmdarr);
        ShellUtil.runCmd(cmdarr);
    }
}
