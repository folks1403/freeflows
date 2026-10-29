# Compilação do teste iPhone

O workflow `Build iPhone test IPA (unsigned)` é disparado manualmente na aba Actions do GitHub. Não precisa de chaves Gemini, senha Apple ou certificado para compilar.

1. Abra Actions, selecione esse workflow e clique Run workflow na branch em que ele estiver publicado.
2. Se a compilação falhar, os logs do Xcode indicam o que corrigir; o projeto ainda não foi validado pelo compilador Apple no Windows.
3. Se passar, baixe o artefato `FreeFlows-iPhone-unsigned` ao final da execução.
4. Extraia `FreeFlows-unsigned.ipa` do ZIP de artefato do GitHub.
5. Esse IPA foi compilado para aparelho real, mas **ainda precisa de assinatura**. Para o teste pessoal a partir do Windows, siga a instalação oficial do AltServer/AltStore Classic e assine/instale com sua Apple Account diretamente na ferramenta. Não coloque senha Apple neste repositório ou no chat. Limites de assinatura/renovação se aplicam.

O workflow só existe depois de publicar os arquivos no GitHub. Prepará-lo localmente não executa uma compilação. O runner macOS tem regras de franquia/custo do GitHub; a execução não garante gratuidade em qualquer conta/plano.

Fontes: https://docs.github.com/en/actions/concepts/billing-and-usage e https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows .

O backend da POC permanece separado. Este projeto não contém `.env`, chaves Gemini, sessão de sala ou credencial de controle. Depois de instalado, o app solicita o convite HTTPS atual da sala. Nesta versão, use o app nativo nos dois MCs; áudio do site e do app ainda não possuem ponte.
