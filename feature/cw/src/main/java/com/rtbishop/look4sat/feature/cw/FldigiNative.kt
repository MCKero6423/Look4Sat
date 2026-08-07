package com.rtbishop.look4sat.feature.cw

/**
 * JNI 绑定: fldigi CW 解码器原生库。
 * 对应 C++ 文件: fldigi_cw_jni.cpp
 */
object FldigiNative {
    init {
        System.loadLibrary("fldigi_cw")
    }

    /** 创建解码器实例, 返回 native handle (long) */
    external fun create(): Long

    /** 销毁解码器 */
    external fun destroy(handle: Long)

    /** 送入 PCM 音频数据 (float[], mono, 8000Hz), 解码 */
    external fun process(handle: Long, audio: FloatArray)

    /** 获取并清空积压的解码文本, 返回空字符串表示无新输出 */
    external fun getDecodedText(handle: Long): String
}