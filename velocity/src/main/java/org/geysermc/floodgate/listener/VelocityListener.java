/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package org.geysermc.floodgate.listener;

import static org.geysermc.floodgate.util.ReflectionUtils.getCastedValue;
import static org.geysermc.floodgate.util.ReflectionUtils.getField;
import static org.geysermc.floodgate.util.ReflectionUtils.getFieldOfType;
import static org.geysermc.floodgate.util.ReflectionUtils.getPrefixedClass;
import static org.geysermc.floodgate.util.ReflectionUtils.getPrefixedClassSilently;
import static org.geysermc.floodgate.util.ReflectionUtils.getValue;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.util.GameProfile;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.geysermc.floodgate.api.ProxyFloodgateApi;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.util.LanguageManager;
import org.geysermc.floodgate.util.VelocityCommandUtil;

public final class VelocityListener {
    private static final Field INITIAL_MINECRAFT_CONNECTION;
    private static final Field INITIAL_CONNECTION_DELEGATE;
    private static final Field CHANNEL;

    static {
        Class<?> initialConnection = getPrefixedClass("connection.client.InitialInboundConnection");
        Class<?> minecraftConnection = getPrefixedClass("connection.MinecraftConnection");
        INITIAL_MINECRAFT_CONNECTION = getFieldOfType(initialConnection, minecraftConnection);

        // Since Velocity 3.1.0
        Class<?> loginInboundConnection =
                getPrefixedClassSilently("connection.client.LoginInboundConnection");
        if (loginInboundConnection != null) {
            INITIAL_CONNECTION_DELEGATE = getField(loginInboundConnection, "delegate");
            Objects.requireNonNull(
                    INITIAL_CONNECTION_DELEGATE,
                    "initial inbound connection delegate cannot be null"
            );
        } else {
            INITIAL_CONNECTION_DELEGATE = null;
        }

        CHANNEL = getFieldOfType(minecraftConnection, Channel.class);
    }

    private final Cache<InboundConnection, FloodgatePlayer> playerCache =
            CacheBuilder.newBuilder()
                    .maximumSize(500)
                    .expireAfterAccess(20, TimeUnit.SECONDS)
                    .build();

    @Inject private ProxyFloodgateApi api;
    @Inject private LanguageManager languageManager;
    @Inject private FloodgateLogger logger;

    @Inject
    @Named("playerAttribute")
    private AttributeKey<FloodgatePlayer> playerAttribute;

    @Inject
    @Named("kickMessageAttribute")
    private AttributeKey<String> kickMessageAttribute;

    @Subscribe(order = PostOrder.EARLY)
    public void onPreLogin(PreLoginEvent event) {
        FloodgatePlayer player = null;
        String kickMessage;
        try {
            InboundConnection connection = event.getConnection();
            if (INITIAL_CONNECTION_DELEGATE != null) {
                // Velocity 3.1.0 added LoginInboundConnection which is used in the login state,
                // but that class doesn't have a Channel field. However, it does have
                // InitialInboundConnection as a field
                connection = getCastedValue(connection, INITIAL_CONNECTION_DELEGATE);
            }
            Object mcConnection = getValue(connection, INITIAL_MINECRAFT_CONNECTION);
            Channel channel = getCastedValue(mcConnection, CHANNEL);

            player = channel.attr(playerAttribute).get();
            kickMessage = channel.attr(kickMessageAttribute).get();
        } catch (Exception exception) {
            logger.error("Failed get the FloodgatePlayer from the player's channel", exception);
            kickMessage = "Failed to get the FloodgatePlayer from the players's Channel";
        }

        if (kickMessage != null) {
            event.setResult(
                    PreLoginEvent.PreLoginComponentResult.denied(Component.text(kickMessage))
            );
            return;
        }

        if (player != null) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            playerCache.put(event.getConnection(), player);
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        FloodgatePlayer player = playerCache.getIfPresent(event.getConnection());
        if (player != null) {
            playerCache.invalidate(event.getConnection());
            event.setGameProfile(new GameProfile(
                    player.getCorrectUniqueId(), player.getCorrectUsername(), new ArrayList<>()));
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onLogin(LoginEvent event) {
        if (event.getResult().isAllowed()) {
            FloodgatePlayer player = api.getPlayer(event.getPlayer().getUniqueId());
            if (player != null) {
                languageManager.loadLocale(player.getLanguageCode());
            }
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onDisconnect(DisconnectEvent event) {
        VelocityCommandUtil.AUDIENCE_CACHE.remove(event.getPlayer().getUniqueId()); //todo
    }
}
