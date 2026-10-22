package com.holdhundredyear.web;

import com.holdhundredyear.common.R;
import com.holdhundredyear.game.model.dto.RuntimeInfoDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 运行时信息（匿名可达，和存档架一起放行）：给"手机一起玩"一个能直接抄的地址。
 *
 * <p>便携版与 mvnw 版共用同一套前端；手机连同一个 Wi-Fi 后打开
 * {@code http://<电脑内网IP>:<端口>} 就能看到同一张存档架。只列内网 IPv4，
 * 不列回环/虚拟网卡/169.254 链路本地地址，避免把玩家带到一个打不开的地址。
 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {

    private final int port;
    private final boolean portable;

    public RuntimeController(@Value("${server.port:8080}") int port,
                             @Value("${spring.profiles.active:}") String profiles) {
        this.port = port;
        this.portable = profiles != null && profiles.contains("portable");
    }

    @GetMapping("/info")
    public R<RuntimeInfoDTO> info() {
        return R.ok(new RuntimeInfoDTO(port, portable, lanUrls(port)));
    }

    /** 本机内网 IPv4 → http 地址，按网卡名与地址排序，去重。 */
    static List<String> lanUrls(int port) {
        List<String> urls = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()
                            && !address.getHostAddress().startsWith("169.254.")) {
                        urls.add("http://" + address.getHostAddress() + ":" + port);
                    }
                }
            }
        } catch (SocketException ignored) {
            // 网卡枚举失败时返回空列表，前端仍可提示玩家去跑 game.cmd lan
        }
        Collections.sort(urls);
        return urls.stream().distinct().toList();
    }
}
