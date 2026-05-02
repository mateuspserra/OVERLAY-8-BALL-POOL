# Overlay Inteligente com Deteccao e Trajetoria

Projeto Android nativo em Kotlin para criar uma camada transparente sobre outros apps, capturar a tela com `MediaProjection`, enviar frames para uma IA externa e desenhar deteccoes/linha de trajetoria por cima da tela.

O app nao abre outro aplicativo dentro dele, nao usa WebView e nao incorpora outro app na interface. Ele funciona como overlay separado usando `WindowManager` e `TYPE_APPLICATION_OVERLAY`.

## Estrutura

- `MainActivity`: tela inicial, status e fluxo de permissoes.
- `PermissionManager`: permissoes de sobreposicao, notificacao e captura de tela.
- `OverlayService`: cria a camada transparente que nao bloqueia toque.
- `FloatingControlService`: botoes flutuantes para esconder marcacoes e parar a leitura.
- `ScreenCaptureService`: foreground service com `mediaProjection`, captura de frames e envio para IA.
- `AIClient`: camada isolada para backend generico, Roboflow Workflow ou troca futura por modelo local.
- `DetectionProcessor`: normaliza retorno da IA e filtra por confianca.
- `TrajectoryEngine`: MVP de linha reta ate primeiro impacto.
- `DetectionOverlayView`: desenha circulos, caixas, labels, linha e ponto de impacto.

## Configuracao local

1. Abra o projeto no Android Studio.
2. Copie `local.properties.example` para `local.properties`.
3. O modo padrao `AI_PROVIDER=local_heuristic` permite testar marcacoes sem backend. Para API externa, configure `AI_ENDPOINT`, `AI_PROVIDER` e, se necessario, `AI_API_KEY`.
4. Rode o app em um dispositivo real. Emuladores podem limitar a captura de tela dependendo da imagem.

O intervalo inicial de captura e `250ms`. O app tenta iniciar o proximo frame assim que a janela minima permitir, mas nao envia novo frame enquanto a requisicao anterior ainda esta em andamento.

Builds `release` sao assinados. Para MVP/teste, o projeto usa `app/signing/mvp-release.keystore`, uma chave publica de teste mantida no repositorio para permitir instalar novas versoes por cima das antigas. Para producao, configure `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` e `RELEASE_KEY_PASSWORD` em `local.properties` ou variaveis de ambiente.

Quando o jogo bloquear captura de tela, use o botao de mira no controle flutuante para abrir o modo manual. Ele desenha uma guia ajustavel por cima da tela sem depender de `MediaProjection`.

O modo manual tem dois modos:

- `NORMAL`: circulo ajustavel com linhas para as seis cacapas estimadas.
- `TABELA`: dois pontos ajustaveis para simular uma jogada de tabela. Ao encostar o ponto branco na borda da mesa, o app calcula a primeira reflexao e pode mostrar uma segunda linha refletida.

Toque em `TRAVAR` depois de posicionar a guia. Nesse estado a guia continua visivel, mas nao recebe toques; o jogo por baixo volta a receber o controle do taco. Para ajustar novamente, toque no botao flutuante de mira.

## Observacoes

- Alguns apps podem bloquear captura de tela e retornar imagem preta.
- Quando isso acontece, o overlay ainda pode aparecer, mas a IA nao recebe pixels reais da tela; o app mostra o estado `Captura bloqueada pelo app`.
- Apos alguns frames protegidos seguidos, a captura e pausada para evitar consumo inutil de bateria/API.
- A trajetoria depende de referencia visual: `aim_line`, `cue_direction`, `target_ball` ou `ghost_ball`.
- Para producao, use `App Android -> Backend proprio -> API de IA` para nao expor chave no APK.
- A fisica avancada ainda nao foi implementada. O MVP desenha linha reta ate a primeira colisao detectada ou limite da tela.
