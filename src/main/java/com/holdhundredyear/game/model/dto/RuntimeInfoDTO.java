package com.holdhundredyear.game.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 运行时信息（存档架上的"手机一起玩"提示）：当前端口、是否便携档、本机可用的
 * 局域网访问地址。只暴露内网地址，不暴露公网 IP 与任何账号信息。
 */
@Data
@AllArgsConstructor
public class RuntimeInfoDTO {

    private int port;

    /** 是否 portable 档（便携包/H2）。 */
    private boolean portable;

    /** 形如 http://192.168.1.5:8080；没有可用内网地址时为空列表。 */
    private List<String> lanUrls;
}
