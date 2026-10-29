# FreeFlows Android — teste nativo

Cliente Java nativo para Android 8+ (API 26), com interface Android, AudioRecord/AudioTrack e WebSocket autenticado. Usa o mesmo convite HTTPS, API e relay PCM16 mono/16 kHz do app iPhone. Não usa WebView. O Galaxy A32 SM-A325M conectado foi identificado com Android 13/API 33.

## Compilar

JDK 17, SDK Android 35/build-tools 35.0.0, Gradle 8.11.1, AGP 8.9.2.
Na pasta android: `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`.
O workflow **Build Android test APK** executa essas etapas e publica o APK de teste em **FreeFlows-Android-debug**. Também roda em pull requests que alterem android/ ou o workflow.

## Instalar e verificar via USB

Com depuração USB autorizada, use `adb devices -l` para identificar o aparelho. Se houver vários dispositivos, sempre informe `-s SERIAL`.

```text
adb -s SERIAL install -r app-debug.apk
adb -s SERIAL install -r app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -e class com.freeflows.android.test.StartupTest com.freeflows.android.test.test/android.test.InstrumentationTestRunner
adb -s SERIAL shell am instrument -w -e class com.freeflows.android.test.ConnectionTest -e invite CONVITE_HTTPS com.freeflows.android.test.test/android.test.InstrumentationTestRunner
adb -s SERIAL shell am start -n com.freeflows.android.test/.MainActivity
```

Proteja/escape o convite conforme o shell. Os testes de abertura não capturam microfone. ConnectionTest entra como público, sem autorizar processamento de áudio, e sai da sala. O teste de conexão depende de uma sala ativa e deve ser executado explicitamente; não é um teste unitário para rodar offline.

## Fluxo no aparelho

Cole o convite atual, digite seu nome, escolha MC/público e entre. Para competir, autorize o envio dos turnos ao Gemini. O microfone só é solicitado ao tocar em Ativar/retomar áudio, e transmite apenas durante confronto ativo. Público não precisa conceder microfone. Ao colocar em segundo plano, o app interrompe captura/reprodução e marca áudio desligado; ao voltar, retome manualmente.

O convite comum não concede controles de organizador. Links de criador com `#control=` habilitam teste com disponíveis e treino solo, com versos roteirizados e voz sintética identificada. O compartilhamento do app remove o token de controle. Use o app nativo em ambos os lados (Android/iPhone); clientes web ainda não têm ponte de áudio com o relay nativo.

## Limites do protótipo

Sem serviço de áudio em segundo plano, hospedagem permanente nem promessa de latência. Sessões e fila de transcrição ficam em memória; fechar o processo perde a sessão/fila. Fila limitada a 15 MB de WAV, três tentativas por trecho, sem armazenamento de voz em disco. Não há garantia de entrega após falha permanente. TTS depende da voz instalada no Android. Bluetooth, chamadas, mixagem, atraso e interoperabilidade de áudio precisam de teste real com fones nos dois aparelhos. O relay passa pelo servidor e não oferece criptografia ponta a ponta.

Os testes do protocolo verificam convites HTTPS sem credenciais na URL, remoção do token de criador ao compartilhar, formato WAV e segmentação por voz/pausa. Não medem qualidade de transcrição nem julgamento real. Nenhuma chave de IA pertence ao APK; as credenciais continuam no servidor.

Um APK debug serve para testes, não para Play Store. Atualizações exigem a mesma chave de assinatura. Não desinstale uma versão com dados para contornar uma assinatura diferente.

A identidade de assinatura de teste é restaurada do segredo ANDROID_DEBUG_KEYSTORE_B64 do repositório; a chave privada não é incluída no código nem nos artefatos. Para compilar localmente com a mesma identidade, defina ANDROID_DEBUG_KEYSTORE_PATH apontando para a cópia privada PKCS12. As senhas padrão android servem apenas para essa chave de desenvolvimento; não reutilize em produção. Perder/substituir a chave impede atualizar instalações anteriores com a mesma identidade. Pull requests de forks sem acesso ao segredo precisam de uma configuração separada de build de validação.
