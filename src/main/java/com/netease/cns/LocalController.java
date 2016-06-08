/**
 * Created by hzzhangdongya on 16-6-6.
 */

package com.netease.cns;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.netease.cns.transport.UnixChannelInitializer;
import com.netease.cns.transport.UnixConnectionInitializer;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.opendaylight.openflowjava.protocol.api.connection.ConnectionConfiguration;
import org.opendaylight.openflowjava.protocol.api.connection.ThreadConfiguration;
import org.opendaylight.openflowjava.protocol.api.connection.TlsConfiguration;
import org.opendaylight.openflowjava.protocol.impl.core.SwitchConnectionProviderImpl;
import org.opendaylight.openflowjava.protocol.impl.core.TcpChannelInitializer;
import org.opendaylight.openflowjava.protocol.impl.core.TcpConnectionInitializer;
import org.opendaylight.openflowjava.protocol.impl.deserialization.DeserializationFactory;
import org.opendaylight.openflowjava.protocol.impl.deserialization.DeserializerRegistryImpl;
import org.opendaylight.openflowjava.protocol.impl.serialization.SerializationFactory;
import org.opendaylight.openflowjava.protocol.impl.serialization.SerializerRegistryImpl;
import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.OvsdbConnectionListener;
import org.opendaylight.ovsdb.lib.impl.OvsdbConnectionService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.common.types.rev130731.FlowModCommand;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.common.types.rev130731.FlowModFlags;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.common.types.rev130731.PortNumber;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.common.types.rev130731.TableId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.config.rev140630.TransportProtocol;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.oxm.rev150225.InPort;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.oxm.rev150225.OpenflowBasicClass;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.oxm.rev150225.OxmMatchType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.oxm.rev150225.match.entries.grouping.MatchEntryBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.oxm.rev150225.match.entry.value.grouping.match.entry.value.InPortCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.oxm.rev150225.match.entry.value.grouping.match.entry.value.in.port._case.InPortBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.oxm.rev150225.match.grouping.MatchBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.BarrierInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.BarrierOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.FlowModInputBuilder;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class LocalController {
    private static final Logger LOG = LoggerFactory.getLogger(LocalController.class);
    private static ListeningExecutorService pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(10));
    private static OFConnectionManager ofConnectionManager = new OFConnectionManager();

    public static void main(String[] args) throws Exception {
        final int ofPort = 6633;
        final int ovsdbPort = 6634;
        // My DEVSTACK ovsdb server address;
        final String connectAddressStr = "10.166.224.11";
        final InetAddress connectAddress = InetAddress.getByName("10.166.224.11");
        final InetAddress listenAddress = InetAddress.getByName("0.0.0.0");
        final String unixServerPath = "/run/openvswitch/br-int.mgmt";
        Boolean connectActive = true;
        Boolean connectionUnixActive = true;

        // 1. start openflow controller to listen local ovs-vswitchd to connect.

        // TODO: to prevent loss of packet, we may have to modify openflowjava library to
        //       support unix domain socket transport, and connect to ovs-vswitched actively.

        if (!connectActive) /* ovs-vsctl set-controller br-int tcp:10.166.228.3:6634 */ {
            SwitchConnectionProviderImpl sc = new SwitchConnectionProviderImpl();
            ConnectionConfigurationImpl cc = new ConnectionConfigurationImpl(
                    listenAddress, ofPort, TransportProtocol.TCP, 30, false/* Not use barrier which cause much delay,
                                                                            * use only when needed.
                                                                            * */);

            sc.setConfiguration(cc);
            sc.setSwitchConnectionHandler(ofConnectionManager);
            sc.startup();
        } else {
            /* Init shared SesDes... */
            SerializerRegistryImpl serializerRegistry = new SerializerRegistryImpl();
            serializerRegistry.init();
            SerializationFactory serializationFactory = new SerializationFactory();
            serializationFactory.setSerializerTable(serializerRegistry);
            DeserializerRegistryImpl deserializerRegistry = new DeserializerRegistryImpl();
            deserializerRegistry.init();
            DeserializationFactory deserializationFactory = new DeserializationFactory();
            deserializationFactory.setRegistry(deserializerRegistry);

            if (!connectionUnixActive) /* ovs-vsctl set-controller br-int ptcp:6633 */ {
                NioEventLoopGroup workerGroup = new NioEventLoopGroup(); // XXX: Use Epoll for performance...

                // OpenFlow java seems assume passive connection only, so active is a bit hardcoded here,
                // However, active connection to brXXX.mgmt unix domain socket seems to be the only way
                // to not interrupt traffic.
                TcpConnectionInitializer tcpConnectionInitializer = new TcpConnectionInitializer(workerGroup);
                TcpChannelInitializer tcpChannelInitializer = new TcpChannelInitializer();
                tcpConnectionInitializer.setChannelInitializer(tcpChannelInitializer);
                tcpChannelInitializer.setSwitchConnectionHandler(ofConnectionManager);
                tcpChannelInitializer.setSerializationFactory(serializationFactory);
                tcpChannelInitializer.setDeserializationFactory(deserializationFactory);
                tcpConnectionInitializer.run();
                tcpConnectionInitializer.initiateConnection(connectAddressStr, ofPort);
            } else /* Connection to default br-XXX.mgmt. */ {
                // NOTE: OVS configure a default 60 seconds echo request timer for
                // this connection will make the test run slowly than TCP since we
                // depend on the echo request as a indication of establishment of
                // connection.
                // Refer to : bridge_ofproto_controller_for_mgmt in bridge.c
                EpollEventLoopGroup workerGroup = new EpollEventLoopGroup();
                UnixConnectionInitializer unixConnectionInitializer = new UnixConnectionInitializer(workerGroup);
                UnixChannelInitializer unixChannelInitializer = new UnixChannelInitializer();
                unixConnectionInitializer.setChannelInitializer(unixChannelInitializer);
                unixChannelInitializer.setSwitchConnectionHandler(ofConnectionManager);
                unixChannelInitializer.setSerializationFactory(serializationFactory);
                unixChannelInitializer.setDeserializationFactory(deserializationFactory);
                unixConnectionInitializer.run();
                unixConnectionInitializer.initiateConnection(unixServerPath);
            }
        }

        // 2. start ovsdb controller to listen local ovsdb-server to connect.
        //    this means we start in passive mode, however, the library also
        //    support active mode, we can try that either.

        // TODO: if we make active connecton for openflow, we'd better also
        // make active connection to ovsdb to make the two connection consistent.

        if (!connectActive) /* ovs-vsctl set-manager tcp:10.166.228.3:6634 */ {
            OvsdbConnectionService ovsdbManager = new OvsdbConnectionService();
            OvsdbConnectionListenerImpl clientListener = new OvsdbConnectionListenerImpl();
            ovsdbManager.registerConnectionListener(clientListener);
            ovsdbManager.startOvsdbManager(6634);
        } else /* ovs-vsctl set-manager ptcp:6634 */ {
            OvsdbConnectionService ovsdbManager = new OvsdbConnectionService();
            OvsdbConnectionListenerImpl clientListener = new OvsdbConnectionListenerImpl();
            OvsdbClient activeClient = ovsdbManager.connect(connectAddress, ovsdbPort);
            if (activeClient != null) {
                LOG.info("Connection to ovsdb server actively successfully...");
                final ListenableFuture databases_future = activeClient.getDatabases();
                databases_future.addListener(new Runnable() {
                    public void run() {
                        try {
                            LOG.info("The ovsdb instance hold: " + databases_future.get());
                        } catch (InterruptedException e) {
                            LOG.error("The get database rpc is interrupted...");
                        } catch (ExecutionException e) {
                            LOG.error("Exception in task");
                        }
                    }
                }, pool);
            }
            else {
                LOG.error("Connection to ovsdb server actively failed...");
            }
        }


        // Start a runnable which will push 1k flows once connection active,
        // we will then benchmark performance by using barrier.
        pool.submit(new Runnable() {
            public void run() {
                while (true) {
                    if (ofConnectionManager.isConnectionActive()) {
                        LOG.info("The openflow switch is active, now start test flowmod performance...");

                        final short OF_VERSION = 4;
                        final long TABLE_ID = 0L;
                        final String COOKIE_MASK_FULL = "18446744073709551615";
                        final long BUFFER_ID = 0xffffffffL;
                        final int TIMEOUT = 0;
                        final int PRIORITY = 0;
                        final long OUTPUT_PORT = 0xffffffffL;
                        final long OUTPUT_GROUP = 0xffffffffL;
                        Date date = new Date();
                        final int FLOW_NUM = 5000;
                        final long BARRIER_TIMEOUT = 5000;
                        final TimeUnit BARRIER_TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

                        //long startTime = date.getTime();
                        // Same date instance getTime call will return same timestamp, call System
                        // interface directly...
                        long startTime = System.currentTimeMillis();
                        LOG.info("FlowMod performance test start at" + startTime);
                        // If the connection is active, push flows to the switch...
                        int i = 0;
                        for (; i < FLOW_NUM; i++) {
                            FlowModInputBuilder fmInputBuild = new FlowModInputBuilder();
                            fmInputBuild.setXid(new Long((long) i));
                            fmInputBuild.setCookie(new BigInteger(new Long((long) i).toString()));
                            fmInputBuild.setCookieMask(new BigInteger(COOKIE_MASK_FULL));
                            InPortBuilder inPortBuilder = new InPortBuilder();
                            inPortBuilder.setPortNumber(new PortNumber(new Long((long) i)));
                            InPortCaseBuilder caseBuilder = new InPortCaseBuilder()
                                    .setInPort(inPortBuilder.build());
                            MatchEntryBuilder matchEntryBuilder = new MatchEntryBuilder()
                                    .setOxmClass(OpenflowBasicClass.class)
                                    .setOxmMatchField(InPort.class)
                                    .setHasMask(false)
                                    .setMatchEntryValue(caseBuilder.build());
                            MatchBuilder matchBuilder = new MatchBuilder()
                                    .setType(OxmMatchType.class)
                                    .setMatchEntry(Lists.newArrayList(matchEntryBuilder.build()));
                            // We should really provide an API that set default entries here, this is TOO
                            // time-consuming to prevent NullPointerException in the serialization logic...
                            fmInputBuild.setMatch(matchBuilder.build());
                            fmInputBuild.setCommand(FlowModCommand.OFPFCADD);
                            fmInputBuild.setVersion(OF_VERSION);
                            fmInputBuild.setTableId(new TableId(TABLE_ID));
                            fmInputBuild.setBufferId(BUFFER_ID);
                            fmInputBuild.setFlags(new FlowModFlags(false, false, false, false, false));
                            fmInputBuild.setHardTimeout(TIMEOUT);
                            fmInputBuild.setIdleTimeout(TIMEOUT);
                            fmInputBuild.setPriority(PRIORITY);
                            fmInputBuild.setOutGroup(OUTPUT_GROUP);
                            fmInputBuild.setOutPort(new PortNumber(OUTPUT_PORT));
                            // XXX: Perhaps here we should have a lock when operating on the connection adapter...
                            // For perftest this is enough.
                            ofConnectionManager.getActiveConnectionAdapter().flowMod(fmInputBuild.build());
                            if (i%1000 == 0) {
                                try {
                                    // Yield some time to let ChannelOutput queue to drain...
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    LOG.error("FlowMod performance test is interruppted...");
                                }
                            }
                        }

                        // Send barrier request and wait for reply to ensure flow programmed.
                        BarrierInputBuilder barrierInputBuilder = new BarrierInputBuilder();
                        barrierInputBuilder.setVersion(OF_VERSION);
                        barrierInputBuilder.setXid((long)FLOW_NUM);
                        Future<RpcResult<BarrierOutput>> barrierResult =
                                ofConnectionManager.getActiveConnectionAdapter().barrier(barrierInputBuilder.build());
                        try {
                            RpcResult<BarrierOutput> output = barrierResult.get(BARRIER_TIMEOUT, BARRIER_TIMEOUT_UNIT);
                            //long finishTime = date.getTime();
                            long finishTime = System.currentTimeMillis();
                            LOG.info("Receive barrier reply with xid " + output.getResult().getXid() + " @ " + finishTime);
                            LOG.info("FlowMod performance test finished at " + finishTime);
                            LOG.info("FlowMod rate is " + 1000*((float)FLOW_NUM)/(float)(finishTime - startTime));
                        } catch (Exception e) {
                            LOG.error("Wait barrier reply timeout... can't obtain FlowMod performance");
                        }

                        break;
                    } else {
                        LOG.info("Switch openflow connection is not active, sleeping...");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            LOG.error("FlowMod performance test is interruppted...");
                        }
                    }
                }
            }
        });

        while (true) {
            LOG.info("Master thread is running....");
            Thread.sleep(1000);
        }
    }

    static class ConnectionConfigurationImpl implements ConnectionConfiguration {

        private final InetAddress address;
        private final int port;
        private final TlsConfiguration tlsConfig;
        private final long switchIdleTimeout;
        private final boolean useBarrier;
        private Object transferProtocol;
        private ThreadConfiguration threadConfig;

        /**
         * Creates {@link ConnectionConfigurationImpl}
         *
         * @param address
         * @param port
         * @param protocol
         * @param switchIdleTimeout
         * @param useBarrier
         */
        public ConnectionConfigurationImpl(final InetAddress address, final int port, final TransportProtocol protocol,
                                           final long switchIdleTimeout, final boolean useBarrier) {
            this.address = address;
            this.port = port;
            this.transferProtocol = protocol;
            this.switchIdleTimeout = switchIdleTimeout;
            this.useBarrier = useBarrier;
            this.tlsConfig = null;
        }

        public InetAddress getAddress() {
            return address;
        }

        public int getPort() {
            return port;
        }

        public Object getTransferProtocol() {
            return transferProtocol;
        }

        /**
         * Used for testing - sets transport protocol
         *
         * @param protocol
         */
        public void setTransferProtocol(final TransportProtocol protocol) {
            this.transferProtocol = protocol;
        }

        public long getSwitchIdleTimeout() {
            return switchIdleTimeout;
        }

        public Object getSslContext() {
            // TODO Auto-generated method stub
            return null;
        }

        public TlsConfiguration getTlsConfiguration() {
            return tlsConfig;
        }

        public ThreadConfiguration getThreadConfiguration() {
            return threadConfig;
        }

        /**
         * @param threadConfig thread model configuration (configures threads used)
         */
        public void setThreadConfiguration(final ThreadConfiguration threadConfig) {
            this.threadConfig = threadConfig;
        }

        public boolean useBarrier() {
            return useBarrier;
        }
    }

    static class OvsdbConnectionListenerImpl implements OvsdbConnectionListener {
        OvsdbClient activeClient = null;

        public void connected(OvsdbClient client) {
            LOG.info("an ovsdb instanced connected...");
            activeClient = client;
            final ListenableFuture databases_future = activeClient.getDatabases();
            databases_future.addListener(new Runnable() {
                public void run() {
                    try {
                        LOG.info("The ovsdb instance hold: " + databases_future.get());
                    } catch (InterruptedException e) {
                        LOG.error("The get database rpc is interrupted...");
                    } catch (ExecutionException e) {
                        LOG.error("Exception in task");
                    }
                }
            }, pool);

        }

        public void disconnected(OvsdbClient client) {
            LOG.info("an ovsdb instanced say byebye...");
            activeClient = null;
        }
    }
}