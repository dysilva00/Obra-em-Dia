# Regras do R8 para a versão final (release).

# ML Kit (leitura de QR): os componentes são descobertos por nome a partir do manifesto
# (ComponentRegistrar). Sem isto, BarcodeScanning.getClient() falha com NullPointerException.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.common.internal.MlKitComponentDiscoveryService { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common** { *; }
-keep class com.google.android.gms.internal.mlkit_common** { *; }
