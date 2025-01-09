package com.ruoyi.quartz.task;

import com.alibaba.fastjson2.JSONObject;
import com.github.t9t.minecraftrconclient.RconClient;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.http.HttpUtils;
import com.ruoyi.server.common.MapCache;
import com.ruoyi.server.common.RconService;
import com.ruoyi.server.domain.PlayerDetails;
import com.ruoyi.server.domain.WhitelistInfo;
import com.ruoyi.server.mapper.WhitelistInfoMapper;
import com.ruoyi.server.service.IPlayerDetailsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ClassName: OnlineTask <br>
 * Description:
 * date: 2024/4/7 下午7:51 <br>
 *
 * @author Administrator <br>
 * @since JDK 1.8
 */
@Slf4j
@Component("onlineTask")
public class OnlineTask {

    @Autowired
    private WhitelistInfoMapper whitelistInfoMapper;
    @Autowired
    private IPlayerDetailsService playerDetailsService;
    @Autowired
    private RedisCache cache;
    @Autowired
    private RconService rconService;

    /**
     * 根据用户uuid同步用户名称
     * Api：<a href="https://sessionserver.mojang.com/session/minecraft/profile/">...</a>{uuid}
     */
    public void syncUserNameForUuid() {
        log.debug("syncUserNameForUuid start");
        ArrayList<String> list = new ArrayList<>();
        // 查询所有正版用户
        WhitelistInfo whitelistInfo = new WhitelistInfo();
        whitelistInfo.setOnlineFlag(1L);
        whitelistInfoMapper.selectWhitelistInfoList(whitelistInfo).forEach(whitelist -> {
            // 查询用户名称
            try {
                String json = HttpUtils.sendGet("https://sessionserver.mojang.com/session/minecraft/profile/" + whitelist.getUserUuid().replace("-", ""));
                if (StringUtils.isNotEmpty(json)) {
                    // json实例化
                    JSONObject jsonObject = JSONObject.parseObject(json);
                    String newName = jsonObject.getString("name");
                    if (newName.equals(whitelist.getUserName())) {
                        return;
                    }
                    // 更新用户名称
                    whitelist.setUserName(newName);
                    list.add(whitelist.getUserName());
                    whitelistInfoMapper.updateWhitelistInfo(whitelist);

                    // 更新玩家详情
                    final PlayerDetails details = new PlayerDetails();
                    details.setWhitelistId(whitelist.getId());
                    final List<PlayerDetails> playerDetails = playerDetailsService.selectPlayerDetailsList(details);

                    if (!playerDetails.isEmpty()) {
                        final PlayerDetails player = playerDetails.get(0);
                        player.setUserName(newName);
                        player.setUpdateTime(new Date());

                        JSONObject data = new JSONObject();
                        if (player.getParameters().isEmpty()) {
                            data = JSONObject.parseObject(player.getParameters());
                            data.getJSONArray("name_history").add(newName);
                        } else {
                            data.put("name_history", new ArrayList<String>() {{
                                add(newName);
                            }});
                            player.setParameters(data.toJSONString());
                        }
                    } else {
                        PlayerDetails player = new PlayerDetails();
                        player.setWhitelistId(whitelist.getId());
                        player.setCreateTime(new Date());
                        player.setQq(whitelist.getQqNum());
                        player.setUserName(newName);
                        player.setParameters("{}");
                        playerDetailsService.insertPlayerDetails(player);
                    }

                }
            } catch (Exception e) {
                log.error("syncUserNameForUuid error", e);
            }
        });
        log.debug("syncUserNameForUuid list: {}", list);
        log.debug("syncUserNameForUuid end");
    }

    /**
     * 根据高密度定时查询在线用户更新最后一次上线时间
     */
    public void monitor() {
        Map<String, RconClient> map = MapCache.getMap();
        if (map == null || map.isEmpty()) {
            return;
        }
        Set<String> onlinePlayer = new HashSet<>();

        for (Map.Entry<String, RconClient> entry : map.entrySet()) {
            String serverName = entry.getKey();
            RconClient rconClient = entry.getValue();
            int retryCount = 0;
            int maxRetries = 3;  // 最大重试次数

            while (retryCount < maxRetries) {
                try {
                    // 先发送一个简单的命令测试连接
                    String testResponse = rconClient.sendCommand("ping");
                    if (testResponse == null) {
                        throw new Exception("Connection test failed");
                    }

                    // 获取在线玩家列表
                    String list = rconClient.sendCommand("list");
                    if (StringUtils.isNotEmpty(list)) {
                        if (list.contains("There are")) {
                            String[] parts = list.split(":");
                            if (parts.length > 1) {
                                String playerList = parts[1].trim();
                                if (!playerList.isEmpty()) {
                                    String[] players = playerList.split(", ");
                                    for (String player : players) {
                                        onlinePlayer.add(player.toLowerCase().trim());
                                    }
                                }
                            }
                        } else if (list.contains("Online (")) {
                            String[] parts = list.split(":");
                            if (parts.length > 1) {
                                String playerList = parts[1].trim();
                                if (!playerList.isEmpty()) {
                                    String[] players = playerList.split(", ");
                                    for (String player : players) {
                                        onlinePlayer.add(player.toLowerCase().trim());
                                    }
                                }
                            }
                        }
                    }
                    // 成功获取数据，跳出重试循环
                    break;

                } catch (Exception e) {
                    retryCount++;
                    if (retryCount >= maxRetries) {
                        log.error("Failed to get online players from server {} after {} retries: {}",
                                serverName, maxRetries, e.getMessage());
                        // 尝试重新建立连接
                        try {
                            rconClient.close();
                            // 重新初始化Rcon连接
                            rconService.reconnect(serverName);
                        } catch (Exception closeEx) {
                            log.error("Failed to close RCON connection for server {}: {}",
                                    serverName, closeEx.getMessage());
                        }
                    } else {
                        log.warn("Retry {} for server {}: {}",
                                retryCount, serverName, e.getMessage());
                        try {
                            // 重试前等待一小段时间
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        if (cache == null) {
            log.error("Cache is not initialized.");
            return;
        }

        // 获取缓存中的在线玩家
        Set<String> cacheOnlinePlayer = cache.getCacheObject("onlinePlayer");
        if (cacheOnlinePlayer == null) {
            cacheOnlinePlayer = new HashSet<>();
        }

        // 找出新上线的玩家（当前在线但缓存中没有的）
        Set<String> newOnlinePlayers = new HashSet<>(onlinePlayer);
        newOnlinePlayers.removeAll(cacheOnlinePlayer);

        // 找出新下线的玩家（缓存中有但当前不在线的）
        Set<String> newOfflinePlayers = new HashSet<>(cacheOnlinePlayer);
        newOfflinePlayers.removeAll(onlinePlayer);

        // 更新上线时间
        if (!newOnlinePlayers.isEmpty()) {
            log.info("New online players: {}", newOnlinePlayers);
            playerDetailsService.updateLastOnlineTimeByUserNames(new ArrayList<>(newOnlinePlayers));
        }

        // 更新离线时间
        if (!newOfflinePlayers.isEmpty()) {
            log.info("New offline players: {}", newOfflinePlayers);
            playerDetailsService.updateLastOfflineTimeByUserNames(new ArrayList<>(newOfflinePlayers));
        }

        // 更新缓存为当前在线玩家
        cache.setCacheObject("onlinePlayer", onlinePlayer);
    }
}
