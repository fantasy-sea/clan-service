package com.fantasysea.clanservice.common.security

/**
 * 设备令牌服务：生成、哈希与校验。
 * 数据库只保存 SHA-256(token)，明文 token 仅在注册响应中返回一次。
 */
interface DeviceTokenService {

    /** 生成新的设备令牌（32 字节安全随机数，URL-safe Base64 无 padding，43 字符）。 */
    fun generate(): String

    /** 返回原始令牌的 SHA-256 十六进制哈希（64 字符）。 */
    fun hash(rawToken: String): String

    /** 常量时间比较原始令牌与库中哈希，避免时序侧信道。 */
    fun matches(rawToken: String, expectedHash: String): Boolean
}
