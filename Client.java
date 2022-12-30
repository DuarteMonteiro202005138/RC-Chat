import java.nio.channels.SelectionKey;

public class Client {
    public String nick;
    public String room;
    public STATE state;
    public SelectionKey key;
    
    public Client(SelectionKey key) {
        nick = null;
        room = null;
        state = STATE.INIT;
        this.key = key;
    }

    public void leaveRoom() {
        state = STATE.OUTSIDE;
    }
}