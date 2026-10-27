# Compilar e instalar

## Duas versões da app

| | Versão de testes (debug) | Versão final (release) |
|---|---|---|
| Nome no telemóvel | Obra em Dia (teste) | Obra em Dia |
| Pacote | `pt.obraemdia.app.debug` | `pt.obraemdia.app` |
| Assinatura | chave de testes do Android Studio | chave própria (ver abaixo) |
| Otimização (R8) | não | sim |

As duas instalam-se lado a lado e **não partilham dados**. Para passar dados de uma para a outra: Cópia de segurança → Guardar na versão de origem, e Restaurar na versão de destino.

## Chave de assinatura (importante)

- Ficheiros: `Documentos\ObraEmDia-assinatura\obra-em-dia.jks` e `keystore.properties` (com as palavras-passe).
- Uma cópia de `keystore.properties` fica na raiz do projeto e está no `.gitignore`. A compilação lê-a daí.
- **Guarde a pasta `ObraEmDia-assinatura` noutro sítio** (Google Drive privado, pen). Sem esta chave não se consegue atualizar a app instalada nos telemóveis dos pais sem a desinstalar. Para atualizar sem ela, seria preciso fazer uma cópia de segurança, desinstalar, instalar e restaurar.
- Certificado: `CN=Obra em Dia, O=Dylan Silva, C=PT`, SHA-256 `c8:f0:5b:13:e1:5a:9b:78:de:a7:97:65:1e:15:f5:66:41:00:7e:a3:2e:35:89:9d:a0:7e:b8:f5:61:32:1a:c1`. Válido até 2054.

## Compilar a versão final

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew :app:assembleRelease
```

Resultado: `app\build\outputs\apk\release\app-release.apk`. Uma cópia com o nome da versão fica em `dist\` (no `.gitignore`).

Antes de entregar, testar a versão release (não só a debug): o R8 pode partir código que usa reflexão. Já aconteceu com o ML Kit; as regras estão em `app/proguard-rules.pro`. Percurso mínimo: arrancar, registar horas, cronómetro, importar PDF de fatura, Ler QR, guardar e restaurar cópia, Experimentar lembrete.

## Atualizar

1. Aumentar `versionCode` (+1) e `versionName` em `app/build.gradle.kts`.
2. Compilar com a mesma chave.
3. Instalar por cima. Os dados mantêm-se e as migrações da base de dados correm sozinhas.

## Instalar no telemóvel

- **Com cabo** (depuração USB ligada): `adb install -r dist\ObraEmDia-1.0.0.apk`
- **Sem cabo**: enviar o `.apk` (por e-mail, Drive ou WhatsApp para si próprio), abri-lo no telemóvel e autorizar "Instalar apps desconhecidas" para essa app quando o Android pedir.

O Play Protect pode avisar que a app é desconhecida. É normal numa app que não vem da Play Store: escolher "Instalar mesmo assim".
