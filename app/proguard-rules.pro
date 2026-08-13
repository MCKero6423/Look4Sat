# ProGuard / R8 rules for the Look4Sat application module.
#
# NOTE: release builds enable minification (isMinifyEnabled=true in the
# convention plugin), so anything whose classes are resolved reflectively or
# through JNI by name MUST be kept here.

# ONNX Runtime (ai.onnxruntime): the Java binding is backed by JNI. Native code
# resolves Java methods/classes by their original names; R8 renaming or
# stripping them causes a hard crash at runtime with no Java stack trace.
# This rule is required by the ONNX Runtime docs for minified Android builds.
# https://onnxruntime.ai/docs/get-started/with-java.html
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
