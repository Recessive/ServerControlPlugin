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

    private DBInterface networkDB = new DBInterface("player_data", true);
    private DBInterface donationDB = new DBInterface("donation_data");

    private PipeHandler assimPipe = new PipeHandler("../network-files/hubPIPEassim");

    // FFA pos: 2000, 2545
    @Override
    public void init(){
        networkDB.connect("../network-files/network_data.db");
        donationDB.connect(networkDB.conn);


        Events.on(PlayerJoin.class, event -> {
            networkDB.loadRow(event.player.uuid);
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

        String name = (String) networkDB.safeGet(uuid,"latestName");
        globalMessage(StringHandler.donatorMessagePrefix(level) + Strings.stripColors(name) + "[gold] just donated to the server!");


        assimPipe.write("donation;" + uuid + "," + level);


        Log.info("Added " + period + (period > 1 ? " months" : " month") + " of donator " + level + " to uuid: " + uuid);
    }

    public void globalMessage(String message){
        Call.sendMessage(message);
        assimPipe.write("say;" + message);
    }
}
