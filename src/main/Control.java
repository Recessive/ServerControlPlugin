package main;

import arc.*;
import arc.net.Server;
import arc.util.*;
import mindustry.core.Version;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType;
import mindustry.game.EventType.*;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.gen.*;
import mindustry.mod.Plugin;
import mindustry.net.*;
import mindustry.net.Net;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;

import static mindustry.Vars.*;
import static mindustry.Vars.player;

public class Control extends Plugin {

    private final Random rand = new Random(System.currentTimeMillis());

    private final DBInterface networkDB = new DBInterface("player_data", true);
    private final DBInterface donationDB = new DBInterface("donation_data");
    private final DBInterface anniversaryDB = new DBInterface("anniversary_uses");

    private final PipeHandler assimPipe = new PipeHandler("../network-files/hubPIPEassim");
    private final PipeHandler campaignPipe = new PipeHandler("../network-files/hubPIPEcampaign");
    private final PipeHandler plaguePipe = new PipeHandler("../network-files/hubPIPEplague");

    // FFA pos: 2000, 2545
    @Override
    public void init(){
        networkDB.connect("../network-files/network_data.db");
        donationDB.connect(networkDB.conn);
        anniversaryDB.connect(networkDB.conn);


        Events.on(PlayerJoin.class, event -> {
            networkDB.loadRow(event.player.uuid());
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
                addDonator(player.uuid(), level, period);

                player.sendMessage("[gold]Key verified! Enjoy your [scarlet]" + period + (period > 1 ? " months" : " month") + "[gold] of donator [scarlet]" + level + "[gold]!");
                donationDB.customUpdate("DELETE FROM donation_data WHERE donateKey=\"" + args[0] + "\";");
                Log.info("Removed key " + args[0] + " from database");
                return;
            }
            player.sendMessage("[accent]Invalid key!");
        });

        handler.<Player>register("anniversary", "<key>", "Use the anniversary key", (args, player) ->{
            if(player.donateLevel != 0){
                player.sendMessage("[accent]Can only redeem key if you aren't a donator!");
                return;
            }
            if(anniversaryDB.hasRow(player.uuid())){
                player.sendMessage("[accent]You have already used this key!");
                return;
            }
            int day = LocalDate.now().getDayOfMonth();
            if(LocalDate.now().getMonthValue() == 3 && day >= 8 && day <= 13){
                if(!args[0].equalsIgnoreCase("apricot")){
                    player.sendMessage("[accent]Invalid key");
                    return;
                }
                networkDB.loadRow(player.uuid());
                networkDB.safePut(player.uuid(),"donatorLevel", 1);
                long currentPeriod = (int) networkDB.safeGet(player.uuid(),"donateExpire") - System.currentTimeMillis()/1000;
                currentPeriod = Math.max(0, currentPeriod);

                networkDB.safePut(player.uuid(),"donateExpire", System.currentTimeMillis()/1000 + 604800 + currentPeriod);
                networkDB.saveRow(player.uuid(), false);
                player.sendMessage("[gold]You now have a week of donator. Thanks for playing!" +
                        "\n[accent]Relog to receive your rank (also don't  tell people the key, just to keep it fun)");
            }else{
                player.sendMessage("[accent]Not the anniversary week!");
            }
        });

    }


        @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("pipe", "[message]", "Send message to all pipes", args -> {
            assimPipe.write(args[0]);
            campaignPipe.write(args[0]);
            plaguePipe.write(args[0]);
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
        campaignPipe.write("donation;" + uuid + "," + level);
        plaguePipe.write("donation;" + uuid + "," + level);



        Log.info("Added " + period + (period > 1 ? " months" : " month") + " of donator " + level + " to uuid: " + uuid);
    }

    public void globalMessage(String message){
        Call.sendMessage(message);
        assimPipe.write("say;" + message);
        campaignPipe.write("say;" + message);
        plaguePipe.write("say;" + message);
    }
}
