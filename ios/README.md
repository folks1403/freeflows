# Free Flows — app nativo para iPhone

**Entrega: projeto-fonte iOS em SwiftUI, ainda não compilado no Xcode e não instalado no iPhone.** Não é um site, PWA ou WebView. Este pacote não contém `.ipa`, assinatura Apple ou certificado. O computador usado nesta criação é Windows e não tem Xcode/SDK iOS.

## O que foi implementado no código

- Tela nativa para colar convite, escolher nome e entrar como MC ou público.
- Arena 1v1, participantes, chaveamento e transcrições.
- Microfone nativo com AVAudioEngine, áudio duplex e processamento de voz do iOS.
- Transporte de áudio nativo via WSS autenticado no servidor, sem Safari ou TURN no caminho da mídia nativa.
- Beat sintético local com época compartilhada; reprodução da melodia enviada pelo criador quando houver arquivo no servidor.
- Treino solo com voz sintética e versos de exemplo; batalha real com dois ou mais MCs disponíveis.
- Microfones livres durante o confronto; somente indicação de vez, sem cronômetro ou botão de encerramento.
- Segmentação de voz para o Gemini tentar reconhecer quatro entradas por MC; julgamento após oito entradas reconhecidas.
- Credenciais de sala no Keychain; nenhuma chave Gemini no app. As duas chaves permanecem no servidor.
- Pausa de áudio ao ir para segundo plano e ação explícita para retomá-lo. A tela permanece acordada enquanto o áudio estiver ativo.

Esses itens descrevem implementação no fonte, não recursos validados num aparelho. Compilação e testes reais ainda são necessários.

## Como chegar à primeira instalação

O caminho oficial de teste pessoal usa um **Mac com Xcode e uma Apple Account**. Pode ser um Mac emprestado ou ambiente macOS autorizado; nenhum serviço pago foi contratado ou iniciado. O iPhone conectado a este Windows não substitui o Xcode nem a assinatura.

1. Transfira esta pasta para o Mac e abra `FreeFlows.xcodeproj` no Xcode atual.
2. No target **FreeFlows > Signing & Capabilities**, escolha sua equipe. Para teste pessoal, a Apple oferece o fluxo de Personal Team, sujeito aos seus limites. Use um Bundle Identifier único se o exemplo `com.freeflows.iphone.test` não estiver disponível.
3. Conecte o iPhone ao Mac, desbloqueie-o e siga os avisos normais de confiança e Developer Mode que o Xcode/Apple solicitarem. Faça login e alterações de segurança pessoalmente; não compartilhe senha, certificados ou códigos no chat.
4. Escolha o iPhone como destino e clique em **Run**. O projeto mira **iPhone com iOS 17 ou superior**, em retrato.
5. Corrija qualquer erro de compilação diagnosticado pelo Xcode antes de tentar a instalação. Esta entrega não foi submetida ao compilador/SDK da Apple.

Referências oficiais: [executar no aparelho](https://help.apple.com/xcode/mac/current/en.lproj/dev5a825a1ca.html), [assinatura e Personal Team](https://help.apple.com/xcode/mac/current/en.lproj/dev60b6fbbc7.html), [Developer Mode](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device/).

### Só tenho Windows

Você pode manter o servidor no Windows e preparar/editar o código. Para produzir o app do iPhone ainda será necessária uma etapa macOS + assinatura. O arquivo `ci/macos-build.workflow.yml` é um exemplo **manual**, não ativado, que pode ser colocado num repositório GitHub em `.github/workflows/` para tentar a compilação em um runner macOS. Limites/custos dependem da sua conta; nenhuma execução foi criada aqui.

Essa compilação é para simulador e **sem assinatura**: ela não gera um app instalável no seu iPhone. A assinatura/distribuição é uma etapa adicional que requer as credenciais e o fluxo Apple apropriados. Não foi configurado TestFlight, App Store, instalação alternativa ou geração de IPA assinada.

## Depois de instalar

1. Deixe o servidor atualizado do Free Flows ligado no Windows (pasta `free-flows-poc`, entregue separadamente no workspace).
2. No app, cole o convite HTTPS atual da sala. O domínio temporário pode mudar quando o túnel reinicia.
3. Escolha nome, papel de MC/público e autorização de áudio. Entre na arena.
4. Toque em **Ativar / retomar áudio** e permita o microfone se for competir. Público não solicita microfone.
5. Para preparar a sala pelo iPhone, o criador deve colar seu link de criador, que inclui `#control=...`. A credencial será guardada no Keychain. Compartilhe somente o convite comum pelo botão de compartilhar do app.
6. Para batalha real, **os dois MCs precisam usar esta versão nativa**. Com dois prontos, o criador pode iniciar a batalha. Para treino solo, o criador escolhe essa opção.
7. Use fones e mantenha o app em primeiro plano. Ao bloquear a tela, atender ligação ou voltar de outro app, pode ser necessário tocar em **Retomar áudio**.

O áudio nativo não tem ponte com o áudio WebRTC do site anterior. Os dois clientes compartilham sala/estado/IA, mas uma batalha com um MC no site e outro no app é rejeitada no início do teste para não simular uma conexão de áudio inexistente. Essa ponte fica para uma integração futura. Para a primeira batalha nativa, são necessários dois iPhones com o app assinado e instalado, ou treino solo em um aparelho.

## Arquitetura

| Arquivo | Responsabilidade |
|---|---|
| `FreeFlowsApp.swift` | Ciclo de vida do app, pausa/retomada |
| `ArenaView.swift` | Interface SwiftUI nativa |
| `RoomModel.swift` | Estado da sala, ações, polling e fila de transcrição |
| `RoomAPI.swift` | HTTPS, autenticação e Keychain |
| `NativeAudio.swift` | AVAudioEngine, conversão PCM, WSS, beat e voz sintética |
| `Models.swift` | Contratos JSON do servidor |
| `Info.plist` | Identidade do app e justificativa do microfone |

Não há pacotes externos no app iOS. O servidor recebeu uma dependência `ws` fixada no lockfile para o relay de áudio. Instale o servidor com `npm ci`, depois execute `npm start`. O endpoint `/health` deve informar `nativeAudio.version = 1`.

### Protocolo de áudio

- Entrada: `wss://SEU_SERVIDOR/native-audio` com header `Authorization: Bearer <sessão>`.
- Cliente envia frames binários PCM16 little endian, mono, 16 kHz; tamanho variável até 8192 bytes.
- Servidor envia 36 bytes ASCII com UUID do MC, seguidos do PCM.
- Somente MCs ativos, conectados e com áudio pronto podem publicar; espectadores apenas recebem.
- O servidor limita tamanho/taxa e evita acumular saída em conexões lentas. Não há compressão de áudio ou retransmissão de pacotes de mídia em nível da aplicação.
- Ao contrário do P2P anterior, essa mídia passa pelo servidor. O servidor pode receber/processar a voz em claro após terminação TLS; não é criptografia ponta a ponta entre os participantes.

Este relay TCP/TLS é uma alternativa experimental de transporte, **não uma SFU WebRTC validada**. Tem maior banda que Opus e pode sofrer atraso por perda de pacotes, buffering e variação de rede. Faltam métricas e adaptação de jitter/banda. Duas fontes PCM nominalmente representam 64 KB/s de entrada e saída proporcional ao público, fora overhead. Não há número de latência medido.

### Transcrição e contagem de entradas

O app segmenta a voz com atividade/pausa e envia WAV ao servidor; o limite de 20 s por segmento reduz tamanho de requisição, sem cortar a voz transmitida ou encerrar uma entrada. Pausa por si só não conta entrada: o Gemini deve reconhecer fim com confiança suficiente. Fala baixa, ruído e respiração podem afetar o detector; a confiança declarada pelo modelo não é calibrada. Contagem de quatro entradas não foi validada com freestyle real.

A fila nativa fica somente em memória, tem limite de 20 MB em base64 e faz até três tentativas por envio. Ao falhar, conserva os trechos e informa que a contagem pode atrasar; ao chegar novo segmento ou retomar processamento, a fila pode voltar a tentar. Sair da sala ou fechar o processo perde pendências. O servidor também possui failover entre as chaves Gemini já configuradas. Duas chaves do mesmo serviço não eliminam indisponibilidade geral.

Não há teto financeiro de uso Gemini. O número de chamadas depende da fala/pausas e da disponibilidade do serviço. Nada foi contratado ou cobrado intencionalmente para criar o app; o uso real depende das cotas da conta.

## O que foi e não foi verificado

Verificações concluídas no Windows:

- **27 testes do servidor passaram**, incluindo novo teste WebSocket com dois MCs simulados e espectador, rejeição sem sessão e rejeição de publicação pelo espectador.
- Sintaxe dos 6 arquivos Swift analisada com tree-sitter-swift, sem erros de parsing detectados.
- `Info.plist` e scheme validados como XML; referências internas do projeto Xcode conferidas.
- Novo servidor respondeu por HTTPS e anunciou o protocolo nativo. As duas conexões Gemini estavam configuradas no status, sem expor segredos.

**Não foram verificados**: compilação Swift com SDK da Apple, assinatura, instalação, layout no iPhone, permissão de microfone real, duplex, volume, Bluetooth, roteamento, latência, sincronismo do beat, qualidade da transcrição/julgamento e reconhecimento de entradas. Parsing Swift não comprova que tipos e APIs compilam no Xcode. Nenhum app foi instalado nem executado no aparelho conectado.

Não há suporte garantido a segundo plano, chamadas telefônicas/interrupções, troca de rota sem retomar áudio, reconexão persistente da batalha após reiniciar o servidor, gravação da tela dentro do app ou operação de campeonato público. Para gravar um teste, use a gravação de tela do iOS e verifique se sua voz/som do app entraram no vídeo; isso também precisa de validação no aparelho.

## Roteiro do primeiro teste real

1. Compile no Xcode para simulador para detectar erros básicos; depois assine e execute no iPhone real.
2. Faça treino solo com fones e confirme captura, beat e retorno sintético sem eco.
3. Instale no segundo iPhone e entre como outro MC. Inicie teste com os dois prontos.
4. Confirme que cada ponta ouve somente o outro MC e o beat, sem realimentação da própria voz.
5. Entre como público num terceiro app nativo: deve ouvir sem pedir microfone nem poder publicar.
6. Teste Wi-Fi/Wi-Fi e Wi-Fi/rede móvel; meça atraso e drift. Registre modelo, iOS, tipo de rede e rota de áudio.
7. Teste suspensão, retomada, perda de rede e Gemini indisponível. A tela deve informar a condição, sem inventar resultado.

Fontes: [AVAudioSession](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playandrecord), [AVAudioEngine](https://developer.apple.com/documentation/avfaudio/avaudioengine), [WebSocket nativo](https://developer.apple.com/documentation/foundation/urlsessionwebsockettask).
