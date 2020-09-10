package ServerControl;

import mindustry.entities.type.Player;

public class CustomPlayer {

    protected Player player;
    protected int playTime;
    public boolean connected;


    public CustomPlayer(Player player, int playTime){
        this.player = player;
        this.playTime = playTime;
        this.connected = true;
    }

}
