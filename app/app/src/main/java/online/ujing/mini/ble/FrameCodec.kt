package online.ujing.mini.ble

/**
 * Cypress CYBLE-20736 透传帧编解码。
 *
 * 帧格式（十六进制字符串，全部字段 2 位）：
 *   [frameNum][total][pkgNum][pkgLen][payload...]
 *
 * ACK 格式：
 *   [frameNum][total][xor(pkgNum)][sum(pkgLen)]["00"|"01"]
 *   最后一个字节：已收齐（frames.size >= total）为 "00"，仍有缺包为 "01"。
 *
 * 逆向来源：`createAckFrame` / 组包函数 `x`，见 bluetoothConnect.js。
 */
object FrameCodec {

    data class Frame(
        val frameNum: Int,
        val total: Int,
        val pkgNum: Int,
        val pkgLen: Int,
        val payload: String,
        val raw: String
    )

    /** 解析单帧；头不足 8 位或字段非十六进制返回 null。 */
    fun parseFrame(rawHex: String): Frame? {
        val hex = rawHex.trim()
        if (hex.length < 8) return null
        return try {
            val frameNum = hex.substring(0, 2).toInt(16)
            val total = hex.substring(2, 4).toInt(16)
            val pkgNum = hex.substring(4, 6).toInt(16)
            val pkgLen = hex.substring(6, 8).toInt(16)
            Frame(frameNum, total, pkgNum, pkgLen, hex.substring(8), hex)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * 构造 ACK。
     * 空列表返回 null（无可确认内容）。
     */
    fun buildAck(frames: List<Frame>): String? {
        if (frames.isEmpty()) return null
        var frameNum = 0
        var total = 0
        var xorPkgNum = 0
        var sumPkgLen = 0
        for (f in frames) {
            frameNum = f.frameNum
            total = f.total
            xorPkgNum = xorPkgNum xor f.pkgNum
            sumPkgLen += f.pkgLen
        }
        val done = if (frames.size < total) "01" else "00"
        return hex2(frameNum) + hex2(total) + hex2(xorPkgNum) + hex2(sumPkgLen) + done
    }

    /** 数值转 2 位大写十六进制（溢出取低 8 位，与官方 zeroFillValue 行为一致）。 */
    private fun hex2(v: Int): String = "%02X".format(v and 0xFF)

    /**
     * 分包组装器。
     *
     * 语义对齐官方 `x` 函数：
     *   - pkgNum == 1 视为新一帧组的开始（重置缓冲）；
     *   - frameNum 必须与首个包一致；
     *   - 同一 pkgNum 去重；
     *   - 收满 total 个包即完成。
     */
    class Assembler {
        private val buffer = LinkedHashMap<Int, Frame>()
        private var currentFrameNum: Int = -1
        private var expectedTotal: Int = -1

        /** 喂入一帧；返回 true 表示该帧组已收齐。 */
        fun offer(frame: Frame): Boolean {
            if (frame.pkgNum == 1) {
                reset()
                currentFrameNum = frame.frameNum
                expectedTotal = frame.total
            }
            if (frame.frameNum != currentFrameNum) return false
            buffer[frame.pkgNum] = frame
            if (buffer.size >= expectedTotal && expectedTotal > 0) {
                return true
            }
            return false
        }

        /** 按 pkgNum 升序返回已收帧（仅在全收齐后使用）。 */
        fun collected(): List<Frame> = buffer.entries.sortedBy { it.key }.map { it.value }

        /** 载荷按包序拼接（payload 原样拼接，不做二次解码）。 */
        fun mergedPayload(): String = collected().joinToString("") { it.payload }

        fun reset() {
            buffer.clear()
            currentFrameNum = -1
            expectedTotal = -1
        }
    }
}
