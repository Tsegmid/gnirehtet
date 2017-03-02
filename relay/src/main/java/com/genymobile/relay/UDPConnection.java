package com.genymobile.relay;

import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

public class UDPConnection extends AbstractConnection {

    public static final long IDLE_TIMEOUT = 2 * 60 * 1000;

    private static final String TAG = UDPConnection.class.getSimpleName();

    private final DatagramBuffer clientToNetwork = new DatagramBuffer(4 * IPv4Packet.MAX_PACKET_LENGTH);
    private final Packetizer networkToClient;
    private IPv4Packet packetForClient;

    private final DatagramChannel channel;
    private final SelectionKey selectionKey;

    private long idleSince;

    public UDPConnection(Route route, Selector selector, IPv4Header ipv4Header, UDPHeader udpHeader) throws IOException {
        super(route);

        networkToClient = new Packetizer(ipv4Header, udpHeader);
        networkToClient.getResponseIPv4Header().switchSourceAndDestination();
        networkToClient.getResponseTransportHeader().switchSourceAndDestination();

        touch();

        SelectionHandler selectionHandler = (selectionKey) -> {
            touch();
            if (selectionKey.isValid() && selectionKey.isReadable()) {
                processReceive();
            }
            if (selectionKey.isValid() && selectionKey.isWritable()) {
                processSend();
            }
            updateInterests();
        };
        channel = createChannel();
        selectionKey = channel.register(selector, SelectionKey.OP_READ, selectionHandler);
    }

    @Override
    public void sendToNetwork(IPv4Packet packet) {
        if (!clientToNetwork.readFrom(packet.getPayload())) {
            Log.d(TAG, route.getKey() + " Cannot send to network, drop packet");
            return;
        }
        updateInterests();
    }

    @Override
    public void disconnect() {
        selectionKey.cancel();
        try {
            channel.close();
        } catch (IOException e) {
            Log.e(TAG, route.getKey() + " Cannot close connection channel", e);
        }
    }

    @Override
    public boolean isExpired() {
        if (mayWrite()) {
            // some writes are pending, do not expire
            return false;
        }
        return System.currentTimeMillis() >= idleSince + IDLE_TIMEOUT;
    }

    private DatagramChannel createChannel() throws IOException {
        Route.Key key = route.getKey();
        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.connect(key.getDestination());
        Log.d(TAG, "Creating new connection: " + route.getKey());
        return channel;
    }

    private void touch() {
        idleSince = System.currentTimeMillis();
    }

    private void processReceive() {
        if (!read()) {
            destroy();
            return;
        }
        pushToClient();
    }

    private void processSend() {
        if (!write()) {
            destroy();
            return;
        }
    }

    private boolean read() {
        try {
            assert packetForClient == null : "The IPv4Packet shares the networkToClient buffer, it must not be corrupted";
            packetForClient = networkToClient.packetize(channel);
            return packetForClient != null;
        } catch (IOException e) {
            Log.e(TAG, route.getKey() + " Cannot read", e);
            return false;
        }
    }

    private boolean write() {
        try {
            return clientToNetwork.writeTo(channel);
        } catch (IOException e) {
            Log.e(TAG, route.getKey() + " Cannot write", e);
            return false;
        }
    }

    private void pushToClient() {
        assert packetForClient != null;
        if (sendToClient(packetForClient)) {
            Log.d(TAG, route.getKey() + " Packet (" + packetForClient.getPayloadLength() + " bytes) sent to client");
            if (Log.isVerboseEnabled()) {
                Log.v(TAG, Binary.toString(packetForClient.getRaw()));
            }
            packetForClient = null;
        }
    }

    protected void updateInterests() {
        if (!selectionKey.isValid()) {
            return;
        }
        int interestingOps = 0;
        if (mayRead()) {
            interestingOps |= SelectionKey.OP_READ;
        }
        if (mayWrite()) {
            interestingOps |= SelectionKey.OP_WRITE;
        }
        selectionKey.interestOps(interestingOps);
    }

    private boolean mayRead() {
        return packetForClient == null;
    }

    private boolean mayWrite() {
        return !clientToNetwork.isEmpty();
    }
}