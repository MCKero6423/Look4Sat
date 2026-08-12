# CW 模块无 native 代码、无按类名注册的 JNI，因此不需要任何 keep 规则。
# DeepCW 走 ONNX Runtime（自带 consumer proguard 规则），前后处理为普通 Kotlin。
