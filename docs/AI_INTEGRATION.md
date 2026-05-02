# Integracao com IA

O app usa a interface `AIClient`, criada por `AIClientFactory`. A implementacao atual envia JPEG base64 para HTTP e converte a resposta em `DetectionResult`.

## Configuracoes

Defina em `local.properties`:

```properties
AI_PROVIDER=local_heuristic
```

Esse modo local usa uma heuristica simples para validar o overlay no 8 Ball Pool sem backend externo. Para usar API externa:

```properties
AI_PROVIDER=generic_json
AI_ENDPOINT=https://seu-backend.example.com/detect
AI_API_KEY=
MIN_DETECTION_CONFIDENCE=0.55
CAPTURE_INTERVAL_MS=250
MAX_UPLOAD_IMAGE_SIZE=960
```

Para Roboflow Workflow em modo de teste:

```properties
AI_PROVIDER=roboflow_workflow
AI_ENDPOINT=https://serverless.roboflow.com/infer/workflows/<workspace>/<workflow_id>
AI_API_KEY=<sua-chave-local>
```

## Requisicao generica

Para `AI_PROVIDER=generic_json`, o app envia:

```json
{
  "image": "<jpeg-base64>",
  "imageBase64": "<jpeg-base64>",
  "imageWidth": 960,
  "imageHeight": 540,
  "apiKey": ""
}
```

## Resposta esperada

O processador aceita `detections`, `predictions` ou `objects`.

Formato interno preferido:

```json
{
  "detections": [
    {
      "className": "cue_ball",
      "confidence": 0.92,
      "x": 430,
      "y": 710,
      "width": 48,
      "height": 48,
      "centerX": 454,
      "centerY": 734
    }
  ]
}
```

Tambem aceita respostas estilo Roboflow com `class`, `confidence`, `x`, `y`, `width` e `height`, tratando `x` e `y` como centro da caixa.

## Classes usadas no MVP

- `cue_ball`: bola branca.
- `aim_line`: linha de mira, prioridade para inferir direcao.
- `cue_direction`: guia ou direcao inferida do taco/mira.
- `target_ball`: fallback para direcao.
- `ghost_ball`: fallback para direcao.
- `pocket`: desenhada, mas ignorada como colisao.
- `spin`: ignorada como colisao.

## Seguranca

Nao embuta chave final no APK. Para producao, use um backend proprio que valide usuario, rate limit e chame a API de IA. A chave em `local.properties` deve ser apenas para testes locais.
