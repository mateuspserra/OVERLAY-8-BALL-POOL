# Testes YOLOv8 vs YOLO26

Este projeto inclui um harness local para comparar modelos YOLO no mesmo dataset Roboflow/YOLOv8 (`.v3-v1.yolov8.zip`).

## Preparar ambiente

```powershell
python -m venv .venv-yolo
.\.venv-yolo\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements-yolo.txt
```

## Validar dataset

```powershell
python tools\yolo_model_ab_test.py --prepare-only
```

Resultado esperado com o dataset atual:

```text
Classes (5): ['cue_ball', 'ghost_ball', 'pocket', 'spin', 'target_ball']
train: 879 images, 879 labels
val: 170 images, 170 labels
test: 121 images, 121 labels
```

Para validar o export YOLO26 baixado do Roboflow:

```powershell
python tools\yolo_model_ab_test.py --dataset-zip "8 ball pool vision.v1i.yolo26.zip" --work-dir build\yolo26_dataset_check --prepare-only
```

Resultado observado:

```text
Classes (1): ['balls']
train: 164 images, 164 labels
val: 47 images, 47 labels
test: 23 images, 23 labels
```

Esse export detecta bolas em geral, mas nao separa bola branca, bola alvo e ghost ball.

## Teste principal

Treina os dois modelos com os mesmos parametros e avalia no split `test`:

```powershell
python tools\yolo_model_ab_test.py --train --epochs 100 --imgsz 960 --batch 8 --device 0
```

Por padrao ele compara:

```text
yolov8n=yolov8n.pt
yolo26n=yolo26n.pt
```

Para comparar os modelos `small`:

```powershell
python tools\yolo_model_ab_test.py --train --epochs 100 --imgsz 960 --batch 8 --device 0 --model yolov8s=yolov8s.pt --model yolo26s=yolo26s.pt
```

Se nao houver GPU, use CPU para um teste menor:

```powershell
python tools\yolo_model_ab_test.py --train --epochs 10 --imgsz 640 --batch 2 --device cpu --predict-limit 20
```

## Comparar pesos ja treinados

Se voce ja tiver um `best.pt` do YOLOv8 e outro do YOLO26:

```powershell
python tools\yolo_model_ab_test.py --model yolov8=path\to\yolov8-best.pt --model yolo26=path\to\yolo26-best.pt --imgsz 960 --device 0
```

## Saidas

Os relatorios ficam em:

```text
build/yolo_ab_test/reports/summary.csv
build/yolo_ab_test/reports/summary.json
```

Campos principais:

- `mAP50`: acerto geral mais permissivo.
- `mAP50_95`: metrica mais exigente para qualidade das caixas.
- `precision`: quantas deteccoes foram corretas.
- `recall`: quantos objetos reais o modelo encontrou.
- `inference_ms`: tempo medio reportado pelo Ultralytics.
- `mean_predict_ms` e `p95_predict_ms`: latencia real medida chamando `predict` em imagens do split.

Para este app, priorize `recall` em `cue_ball`, `target_ball` e `ghost_ball`, desde que a latencia continue aceitavel.
