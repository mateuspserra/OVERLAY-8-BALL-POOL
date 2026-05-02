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
3. Configure `AI_ENDPOINT`, `AI_PROVIDER` e, se necessario, `AI_API_KEY`.
4. Rode o app em um dispositivo real. Emuladores podem limitar a captura de tela dependendo da imagem.

O intervalo inicial de captura e `1000ms`. O app nao envia novo frame enquanto a requisicao anterior ainda esta em andamento.

## Observacoes

- Alguns apps podem bloquear captura de tela e retornar imagem preta.
- A trajetoria depende de referencia visual: `aim_line`, `cue_direction`, `target_ball` ou `ghost_ball`.
- Para producao, use `App Android -> Backend proprio -> API de IA` para nao expor chave no APK.
- A fisica avancada ainda nao foi implementada. O MVP desenha linha reta ate a primeira colisao detectada ou limite da tela.
