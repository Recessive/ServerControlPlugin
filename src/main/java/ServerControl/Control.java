package ServerControl;

import arc.*;
import arc.net.Server;
import arc.util.*;
import mindustry.core.Version;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.type.*;
import mindustry.game.EventType;
import mindustry.game.EventType.*;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.net.Net;
import mindustry.plugin.Plugin;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;

import static mindustry.Vars.*;
import static mindustry.Vars.player;

public class Control extends Plugin{

    private Random rand = new Random(System.currentTimeMillis());

    private double counter = 0f;

    private HashMap<String, CustomPlayer> players = new HashMap<>();

    private DBInterface networkDB = new DBInterface("player_data", true);
    private DBInterface donationDB = new DBInterface("donation_data");

    private PipeHandler assimPipe = new PipeHandler("../network-files/hubPIPEassim");

    // FFA pos: 2000, 2545
    @Override
    public void init(){
        networkDB.connect("../network-files/network_data.db");
        donationDB.connect(networkDB.conn);



        Events.on(Trigger.update, () -> {
            counter += Time.delta();
            if(Math.round(counter) % (60*60) == 0){
                for(Player player : playerGroup.all()){
                    players.get(player.uuid).playTime += 1;
                    Call.setHudTextReliable(player.con, "[accent]Play time: [scarlet]" + players.get(player.uuid).playTime + "[accent] mins.");
                }
            }
        });


        Events.on(PlayerJoin.class, event -> {
            networkDB.loadRow(event.player.uuid);
            players.put(event.player.uuid, new CustomPlayer(event.player, (int) networkDB.safeGet(event.player.uuid, "playTime")));
            int dLevel = (int) networkDB.safeGet(event.player.uuid, "donatorLevel");
            if(dLevel != 0 && donationExpired(event.player.uuid)){
                event.player.sendMessage("\n[accent]You're donator rank has expired!");
                networkDB.safePut(event.player.uuid,"donatorLevel", 0);
                dLevel = 0;
            }
            networkDB.safePut(event.player.uuid,"latestName", Strings.stripColors(event.player.name));
            event.player.name = StringHandler.donatorMessagePrefix(dLevel) + Strings.stripColors(event.player.name);
            Call.setHudTextReliable(event.player.con, "[accent]Play time: [scarlet]" + players.get(event.player.uuid).playTime + "[accent] mins.");
        });

        Events.on(EventType.PlayerLeave.class, event ->{
            savePlayerData(event.player.uuid);
            players.get(event.player.uuid).connected = false;
        });

        Events.on(EventType.PlayerDonateEvent.class, event ->{
            newDonator(event.email, event.uuid, event.level, event.amount);
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("d", "<key>", "Activate a donation key", (args, player) ->{
            if(donationDB.hasRow(args[0])){
                donationDB.loadRow(args[0]);
                int level = (int) donationDB.safeGet(args[0],"level");
                int period = (int) donationDB.safeGet(args[0],"period");
                addDonator(player.uuid, level, period);

                player.sendMessage("[gold]Key verified! Enjoy your [scarlet]" + period + (period > 1 ? " months" : " month") + "[gold] of donator [scarlet]" + level + "[gold]!");
                donationDB.customUpdate("DELETE FROM donation_data WHERE donateKey=\"" + args[0] + "\";");
                Log.info("Removed key " + args[0] + " from database");
                return;
            }
            player.sendMessage("[accent]Invalid key!");
        });

    }


        @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("pipe", "[message]", "Send message to all pipes", args -> {
            assimPipe.write(args[0]);
            Log.info("Message sent.");
        });

        handler.register("setplaytime", "<uuid> <playtime>", "Set the play time of a player", args -> {
            int newTime;
            try{
                newTime = Integer.parseInt(args[1]);
            }catch(NumberFormatException e){
                Log.info("Invalid playtime input '" + args[1] + "'");
                return;
            }

            if(!networkDB.entries.containsKey(args[0])){
                networkDB.loadRow(args[0]);
                networkDB.safePut(args[0],"playtime", newTime);
                networkDB.saveRow(args[0]);
            }else{
                Player player = players.get(args[0]).player;
                players.get(args[0]).playTime = newTime;
                Call.setHudTextReliable(player.con, "[accent]Play time: [scarlet]" + players.get(player.uuid).playTime + "[accent] mins.");
            }
            Log.info("Set uuid " + args[0] + " to have play time of " + args[1] + " minutes");

        });

        handler.register("add_donator", "<uuid> <level> <period>", "Adds a donator", args -> {
            int level;
            try{
                level = Integer.parseInt(args[1]);
            }catch(NumberFormatException e){
                Log.info("Invalid xp input '" + args[1] + "'");
                return;
            }

            int period;
            try{
                period = Integer.parseInt(args[2]);
            }catch(NumberFormatException e){
                Log.info("Invalid period input '" + args[2] + "'");
                return;
            }

            if(!networkDB.hasRow(args[0])){
                Log.info("No uuid: " + args[0] + " in database");
                return;
            }

            addDonator(args[0], level, period);
            Log.info("Set uuid " + args[0] + " to have donator level of " + args[1] + " for " + period + " months");

        });
    }


    void savePlayerData(String uuid){
        if(!networkDB.entries.containsKey(uuid)){
            if(players.containsKey(uuid)){
                Log.info(uuid + " data already saved!");
            }else{
                Log.info(uuid + " does not exist in player object or data!");
            }

            return;
        }

        Log.info("Saving " + uuid + " data...");
        CustomPlayer ply = players.get(uuid);
        networkDB.safePut(uuid,"playtime", ply.playTime, true);
        networkDB.saveRow(uuid);
    }

    boolean donationExpired(String uuid){ return (int) networkDB.safeGet(uuid,"donateExpire") <= System.currentTimeMillis()/1000; }

    void newDonator(String email, String uuid, int level, int amount){

        if(!networkDB.hasRow(uuid)){
            Log.info("uuid does not exist in database!");
            byte[] b = new byte[8];
            rand.nextBytes(b);
            String donateKey = String.valueOf(new BigInteger(b)); // No need to worry about collisions now, at least not for 239847 years.
            donateKey = donateKey.replace("-","");
            donationDB.addRow(donateKey);
            donationDB.loadRow(donateKey);
            donationDB.safePut(donateKey,"level", level);
            donationDB.safePut(donateKey,"period", amount);
            donationDB.saveRow(donateKey);
            Events.fire(new EventType.CustomEvent(new String[]{"Donation failed", email, donateKey}));
            return;
        }

        addDonator(uuid, level, amount);
        Events.fire(new EventType.CustomEvent(new String[]{"Donation success", email}));

    }

    void addDonator(String uuid, int level, int period){
        networkDB.loadRow(uuid);

        boolean levelWasZero = (int) networkDB.safeGet(uuid,"donatorLevel") == 0;

        if(level > (int) networkDB.safeGet(uuid,"donatorLevel")){
            Log.info("Most recent donation outranks previous.");
            networkDB.safePut(uuid,"donateExpire", 0);
        };
        networkDB.safePut(uuid,"donatorLevel", level);
        long currentPeriod = (int) networkDB.safeGet(uuid,"donateExpire") - System.currentTimeMillis()/1000;
        currentPeriod = Math.max(0, currentPeriod);

        networkDB.safePut(uuid,"donateExpire", System.currentTimeMillis()/1000 + 2592000*period + currentPeriod);
        networkDB.saveRow(uuid, false);

        if(players.containsKey(uuid)){
            players.get(uuid).player.name = (levelWasZero ? StringHandler.donatorMessagePrefix(level) : "") + players.get(uuid).player.name;
            globalMessage(players.get(uuid).player.name + "[gold] just donated to the server!");
        }else{
            String name = (String) networkDB.safeGet(uuid,"latestName");
            globalMessage(StringHandler.donatorMessagePrefix(level) + Strings.stripColors(name) + "[gold] just donated to the server!");
        }

        assimPipe.write("donation;" + uuid + "," + level);


        Log.info("Added " + period + (period > 1 ? " months" : " month") + " of donator " + level + " to uuid: " + uuid);
    }

    public void globalMessage(String message){
        Call.sendMessage(message);
        assimPipe.write("say;" + message);
    }
}
