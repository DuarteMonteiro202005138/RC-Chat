import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class ChatServer {
    /* Pre-allocated buffer for the received data */
    private static final ByteBuffer buffer = ByteBuffer.allocate(16384);

    /* Decoder for incoming text - assuming UTF-8 */
    private static final Charset charset = Charset.forName("UTF8");
    private static final CharsetDecoder decoder = charset.newDecoder();

    public static void main(String args[]) throws Exception {
        /* Parse port from CLI */
        int port = Integer.parseInt(args[0]);

        try {
            ServerSocketChannel ssc = ServerSocketChannel.open();

            /* Set ssc to non-blocking so we can use select */
            ssc.configureBlocking(false);

            /* Get the socket connected to the channel and bind it to the listening port */
            ServerSocket ss = ssc.socket();
            InetSocketAddress isa = new InetSocketAddress(port);
            ss.bind(isa);

            /* Create a new Selector for selecting */
            Selector selector = Selector.open();

            /* Register the ServerSocketChannel, so we can listen for incoming connections */
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Listening on port " + port);

            while(true) {
                /* Check if we had any activity, either an incoming connection or incoming data */
                int num = selector.select();

                /* If we don't have any activity, loop around */
                if (num == 0)
                    continue;
                
                /* Get the keys corresponding to the detected activity and process them iteratively */
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    /* Get a key representing one of the bits of IO activity */
                    SelectionKey key = it.next();

                    /* Check the kind of activity */
                    if (key.isAcceptable()) {
                        /* Incoming connection, registering socket with selector to listen for input on it */
                        Socket s = ss.accept();
                        System.out.println("Got connection from " + s);

                        /* Make it non-blocking */
                        SocketChannel sc = s.getChannel();
                        sc.configureBlocking(false);

                        /* Register with selector, for reading */
                        sc.register(selector, SelectionKey.OP_READ);
                    }
                    else if (key.isReadable()) {
                        /* Incoming data on a connection, processing it */
                        SocketChannel sc = null;

                        try {
                            sc = (SocketChannel)key.channel();
                            boolean ok = processInput(sc, selector, key);

                            /* If connection is dead, remove from selector and close it */
                            if (!ok) {
                                key.cancel();
                                Socket s = null;

                                try {
                                    s = sc.socket();
                                    System.out.println("Closing connection to " + s);
                                    s.close();
                                }
                                catch (IOException ie) {
                                    System.err.println("Error closing socket " + s + ": " + ie);
                                }
                            }
                        }
                        catch (IOException ie) {
                            /* Remove this channel from the selector */
                            key.cancel();
                            try {
                                sc.close();
                            }
                            catch (IOException ie2) {
                                System.out.print(ie2);
                            }
                            System.out.print("Closed " + sc);
                        }
                    }
                }
                keys.clear();
            }
        }
        catch (IOException ie) {
            System.err.println(ie);
        }
    }

    /* Read message from socket and send it to stdout */
    private static boolean processInput(SocketChannel sc, Selector selector, SelectionKey key) throws IOException {
        /* Read message to buffer */
        buffer.clear();
        sc.read(buffer);
        buffer.flip();

        /* If no data, close connection */
        if (buffer.limit() == 0)
            return false;

        if (key.attachment() == null)
            Client newClient = new Client(key);

        Client client = (Client)key.attachment();

        String input = decoder.decode(buffer).toString();
        buffer.flip();

        String[] tokens = input.split(" ");
        String server_msg;

        if (input.charAt(0) == ' ')
            server_msg = message(client, input);
        else if (tokens[0].startsWith("/")) {
            String command = tokens[0].replace("\n", "");
            if (command.startsWith("//"))
                server_msg = message(client, input.substring(1));
            else {
                Map<String, Function<String[], String>> commands = new HashMap<>();
                commands.put("/nick", tokens -> nick(client, tokens));
                commands.put("/join", tokens -> join(client, tokens));
                commands.put("/leave", tokens -> leave(client, tokens));
                commands.put("/bye", tokens -> bye(client, tokens));

                server_msg = commands.getOrDefault(command, tokens -> "ERROR\n").apply(tokens);
            }
        }
        else 
            server_msg = message(client, input);

        buffer.clear();
        buffer.put(server_msg.getBytes());
        buffer.flip();
        sc.write(buffer);
        return true;
    }

    static String message(Client client, String input) {
        if (client.state != STATE.INSIDE)
            return "ERROR\n";
        return String.format("MESSAGE %s %s", client.nick, input);
    }

    static String nick(Client client, String[] tokens) {
        if (tokens.length != 2)
            return "ERROR\n";
        String newNick = tokens[1].replace("\n", "");
        client.nick = newNick;
        return "OK\n";
    }

    static String bye(Client client, String[] tokens) {
        if (tokens.length != 1)
            return "ERROR\n";
        client.leaveRoom();
        return "BYE\n";
    }
}
