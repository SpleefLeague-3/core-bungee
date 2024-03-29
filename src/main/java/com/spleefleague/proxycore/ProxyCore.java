package com.spleefleague.proxycore;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.spleefleague.coreapi.chat.Chat;
import com.spleefleague.coreapi.utils.packet.bungee.refresh.PacketBungeeRefreshServerList;
import com.spleefleague.proxycore.chat.ChatChannel;
import com.spleefleague.proxycore.chat.ProxyChat;
import com.spleefleague.proxycore.command.DebugCommand;
import com.spleefleague.proxycore.command.PurchaseCommand;
import com.spleefleague.proxycore.droplet.DropletManager;
import com.spleefleague.proxycore.game.challenge.ChallengeManager;
import com.spleefleague.proxycore.game.session.BattleSessionManager;
import com.spleefleague.proxycore.game.arena.ArenaManager;
import com.spleefleague.proxycore.game.leaderboard.LeaderboardManager;
import com.spleefleague.proxycore.game.queue.QueueManager;
import com.spleefleague.proxycore.infraction.ProxyInfractionManager;
import com.spleefleague.proxycore.listener.ConnectionListener;
import com.spleefleague.proxycore.listener.SpigotPluginListener;
import com.spleefleague.proxycore.packet.PacketManager;
import com.spleefleague.proxycore.party.ProxyPartyManager;
import com.spleefleague.proxycore.player.PlayerManager;
import com.spleefleague.proxycore.player.ProxyDBPlayer;
import com.spleefleague.proxycore.player.ProxyCorePlayer;
import com.spleefleague.proxycore.player.ranks.ProxyRankManager;
import com.spleefleague.proxycore.season.SeasonManager;
import com.spleefleague.proxycore.ticket.TicketManager;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author NickM13
 * @since 6/6/2020
 */
public class ProxyCore extends Plugin {

    private static ProxyCore instance;

    private static final SortedMap<String, ServerInfo> lobbyServers = new TreeMap<>();
    private static final SortedMap<String, ServerInfo> minigameServers = new TreeMap<>();
    private static ScheduledTask serverPingTask;

    public static ProxyCore getInstance() {
        return instance;
    }

    private MongoDatabase database;

    private PlayerManager<ProxyCorePlayer> playerManager;
    private final ProxyPartyManager partyManager = new ProxyPartyManager();

    private final LeaderboardManager leaderboardManager = new LeaderboardManager();
    private final ProxyInfractionManager infractionManager = new ProxyInfractionManager();
    private final ArenaManager arenaManager = new ArenaManager();
    private final QueueManager queueManager = new QueueManager();
    private final ProxyRankManager rankManager = new ProxyRankManager();
    private final PacketManager packetManager = new PacketManager();
    private final ProxyChat chat = new ProxyChat();
    private final SeasonManager seasonManager = new SeasonManager();
    private final ChallengeManager challengeManager = new ChallengeManager();
    private final TicketManager ticketManager = new TicketManager();

    private final DropletManager dropletManager = new DropletManager();

    @Override
    public void onLoad() {
        super.onLoad();
    }

    @Override
    public void onEnable() {
        instance = this;

        initMongo();
        initCommands();

        getProxy().getPluginManager().registerListener(this, new ConnectionListener());
        getProxy().getPluginManager().registerListener(this, new SpigotPluginListener());

        BattleSessionManager.init();

        dropletManager.init();
        seasonManager.init();
        challengeManager.init();

        playerManager = new PlayerManager<>(ProxyCorePlayer.class, getDatabase().getCollection("Players"));
        rankManager.init();
        leaderboardManager.init();
        infractionManager.init();
        arenaManager.init();
        queueManager.init();
        packetManager.init();
        ticketManager.init();

        ProxyCore.getInstance().getProxy().getScheduler().schedule(ProxyCore.getInstance(), () -> {
            for (ProxyCorePlayer pcp : playerManager.getAll()) {
                pcp.updateTempRanks();
            }
        }, 5L, 5L, TimeUnit.MINUTES);

        serverPingTask = ProxyCore.getInstance().getProxy().getScheduler().schedule(ProxyCore.getInstance(), () -> {
            Set<String> toFindLobby = Sets.newHashSet(lobbyServers.keySet());
            Set<String> toFindMinigame = Sets.newHashSet(minigameServers.keySet());

            for (ServerInfo server : getProxy().getServersCopy().values()) {
                String name = server.getName();
                if (name.toLowerCase().startsWith("lobby")) {
                    server.ping((serverPing, throwable) -> {
                        if (serverPing != null) {
                            lobbyServers.put(name, server);
                            onServerConnect(server);
                        } else {
                            lobbyServers.remove(name);
                        }
                    });
                    toFindLobby.remove(name);
                } else if (name.toLowerCase().startsWith("minigame")) {
                    server.ping((serverPing, throwable) -> {
                        if (serverPing != null) {
                            minigameServers.put(name, server);
                            onServerConnect(server);
                        } else {
                            minigameServers.remove(name);
                        }
                    });
                    toFindMinigame.remove(name);
                }
            }
            for (String name : toFindLobby) {
                lobbyServers.remove(name);
                onServerDisconnect(name);
            }
            for (String name : toFindMinigame) {
                minigameServers.remove(name);
                onServerDisconnect(name);
            }
            packetManager.sendPacket(new PacketBungeeRefreshServerList(Lists.newArrayList(lobbyServers.keySet()), Lists.newArrayList(minigameServers.keySet())));
            // TODO: Probably increase this value on release?
        }, 0, 10, TimeUnit.SECONDS);
    }

    public void onServerConnect(ServerInfo serverInfo) {
        packetManager.connect(serverInfo);
    }

    public void onServerDisconnect(String name) {
        packetManager.disconnect(name);
    }

    @Override
    public void onDisable() {
        BattleSessionManager.close();

        challengeManager.close();
        playerManager.close();
        leaderboardManager.close();
        arenaManager.close();
        queueManager.close();
        rankManager.close();
        infractionManager.close();
        packetManager.close();
        seasonManager.close();
        ticketManager.close();

        serverPingTask.cancel();
    }

    public ProxyInfractionManager getInfractions() {
        return infractionManager;
    }

    public LeaderboardManager getLeaderboards() {
        return leaderboardManager;
    }

    public QueueManager getQueueManager() {
        return queueManager;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public ProxyRankManager getRankManager() {
        return rankManager;
    }

    public ProxyChat getChat() {
        return chat;
    }

    public ChallengeManager getChallengeManager() {
        return challengeManager;
    }

    public TicketManager getTicketManager() {
        return ticketManager;
    }

    public List<ServerInfo> getLobbyServers() {
        return Lists.newArrayList(lobbyServers.values());
    }

    public List<ServerInfo> getMinigameServers() {
        return Lists.newArrayList(minigameServers.values());
    }

    public ServerInfo getServerByName(String name) {
        if (lobbyServers.containsKey(name)) {
            return lobbyServers.get(name);
        }
        if (minigameServers.containsKey(name)) {
            return minigameServers.get(name);
        }
        return null;
    }

    /**
     * Connect to the Mongo database based on the mongo.cfg file
     * that should be in the server's folder
     */
    public void initMongo() {
        Properties properties = new Properties();

        String mongoPath = System.getProperty("user.dir") + "/mongo.cfg";

        try (FileInputStream inputStream = new FileInputStream(mongoPath)) {
            properties.load(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String connectionString = properties.getProperty("mongodb.uri", "mongodb://localhost:27017");
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new com.mongodb.ConnectionString(connectionString))
                .build();

        MongoClient mongoClient = MongoClients.create(settings);

        database = mongoClient.getDatabase("SpleefLeague");
    }

    private void initCommands() {
        getProxy().getPluginManager().registerCommand(this, new DebugCommand());
        getProxy().getPluginManager().registerCommand(this, new PurchaseCommand());
    }

    public MongoDatabase getDatabase() {
        return database;
    }

    public PlayerManager<ProxyCorePlayer> getPlayers() {
        return playerManager;
    }

    public ProxyPartyManager getPartyManager() {
        return partyManager;
    }

    public DropletManager getDropletManager() {
        return dropletManager;
    }

    public SeasonManager getSeasonManager() {
        return seasonManager;
    }

    public void sendMessage(String text) {
        playerManager.getAll().forEach(pcp -> {
            if (pcp.getPlayer() != null) {
                pcp.getPlayer().sendMessage(new TextComponent(text));
            }
        });
    }

    public void sendMessage(TextComponent text) {
        sendMessage(ChatChannel.GLOBAL, text);
    }

    public void sendMessage(ChatChannel channel, TextComponent text) {
        TextComponent component = new TextComponent();
        if (channel.isBaseTagEnabled()) component.addExtra(getChatTag());
        component.setColor(ChatColor.GRAY);
        component.addExtra(text);
        playerManager.getAll().forEach(pcp -> {
            if (channel.isActive(pcp)) {
                pcp.getPlayer().sendMessage(component);
            }
        });
    }

    public static String getChatTag() {
        //return "仳 " + Chat.DEFAULT;
        return Chat.TAG_BRACE + "[" + Chat.TAG + "SpleefLeague" + Chat.TAG_BRACE + "] " + Chat.DEFAULT;
    }

    public void sendMessage(ProxyDBPlayer pcp, String text) {
        if (pcp.getPlayer() == null) return;
        pcp.getPlayer().sendMessage(new TextComponent(getChatTag() + text));
    }

    public void sendMessage(ProxyDBPlayer pcp, TextComponent text) {
        if (pcp.getPlayer() == null) return;
        TextComponent textComp = new TextComponent(getChatTag());
        text.setColor(ChatColor.GRAY);
        textComp.addExtra(text);
        pcp.getPlayer().sendMessage(textComp);
    }

    public void sendMessageError(ProxyDBPlayer pcp, TextComponent text) {
        if (pcp.getPlayer() == null) return;
        TextComponent text2 = new TextComponent(getChatTag());
        text.setColor(ChatColor.RED);
        text2.addExtra(text);
        text2.addExtra(ChatColor.RED + "!");
        pcp.getPlayer().sendMessage(text2);
    }

    public void sendMessageSuccess(ProxyDBPlayer pcp, TextComponent text) {
        if (pcp.getPlayer() == null) return;
        TextComponent text2 = new TextComponent(getChatTag());
        text.setColor(ChatColor.GREEN);
        text2.addExtra(text);
        pcp.getPlayer().sendMessage(text2);
    }

    public void sendMessageInfo(ProxyDBPlayer pcp, TextComponent text) {
        if (pcp.getPlayer() == null) return;
        TextComponent text2 = new TextComponent(getChatTag());
        text.setColor(ChatColor.YELLOW);
        text2.addExtra(text);
        pcp.getPlayer().sendMessage(text2);
    }

    public PacketManager getPacketManager() {
        return packetManager;
    }

}
