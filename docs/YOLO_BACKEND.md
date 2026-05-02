# Backend YOLO local

O status `IA local_heuristic local retornou 0 objetos` significa que o APK nao esta usando YOLO. Ele caiu no modo local porque `AI_ENDPOINT` nao foi configurado no `local.properties`.

Para usar YOLOv8/YOLO26, use a Hosted API do Roboflow ou rode um backend HTTP no computador e configure o app Android para chamar esse endpoint.

## Opcao A: Roboflow Hosted API

O trecho do Roboflow com:

```python
CLIENT.infer(your_image.jpg, model_id="8-ball-pool-vision/1")
```

corresponde ao endpoint REST de deteccao:

```text
https://detect.roboflow.com/8-ball-pool-vision/1
```

Configure o `local.properties` assim:

```properties
AI_PROVIDER=roboflow_model
AI_ENDPOINT=https://detect.roboflow.com/8-ball-pool-vision/1
AI_API_KEY=<sua-chave-roboflow>
```

Depois reinstale/recompile o APK. Como esses valores viram `BuildConfig`, mudar `local.properties` sem rebuild nao muda o app instalado.

Observacao: o zip `8 ball pool vision.v1i.yolo26.zip` exporta um dataset com apenas uma classe:

```text
balls
```

Ele deve conseguir desenhar caixas nas bolas, mas nao identifica separadamente `cue_ball`, `target_ball` e `ghost_ball`. Sem essas classes separadas, o app pode mostrar deteccoes, mas a trajetoria automatica tende a ficar sem direcao.

## Opcao B: Backend YOLO local

### 1. Instalar dependencias

```powershell
python -m venv .venv-yolo
.\.venv-yolo\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements-yolo.txt
```

### 2. Treinar ou apontar para um peso

O arquivo `.v3-v1.yolov8.zip` e um dataset, nao e um modelo treinado. Voce precisa de um `best.pt`.

Para treinar rapido usando o harness do projeto:

```powershell
python tools\yolo_model_ab_test.py --train --model pool_v8=yolov8n.pt --epochs 100 --imgsz 960 --batch 8 --device 0
```

O peso treinado fica em:

```text
build/yolo_ab_test/runs/train/pool_v8/weights/best.pt
```

### 3. Rodar o servidor

```powershell
python tools\yolo_detect_server.py --model build\yolo_ab_test\runs\train\pool_v8\weights\best.pt --host 0.0.0.0 --port 8765 --imgsz 960 --conf 0.25 --device 0
```

Sem GPU:

```powershell
python tools\yolo_detect_server.py --model build\yolo_ab_test\runs\train\pool_v8\weights\best.pt --host 0.0.0.0 --port 8765 --imgsz 960 --conf 0.25 --device cpu
```

### 4. Configurar o app

No `local.properties`, coloque o IP do computador na mesma rede do celular:

```properties
AI_PROVIDER=generic_json
AI_ENDPOINT=http://192.168.0.10:8765/detect
AI_API_KEY=
```

### 5. Confirmar no overlay

Quando estiver correto, o status deve mudar de:

```text
IA local_heuristic local retornou 0 objetos
```

para algo como:

```text
IA generic_json
```

ou mostrar deteccoes recebidas. Se aparecer `Frame OK ... IA generic_json retornou 0 objetos`, a captura chegou ao YOLO, mas o modelo nao encontrou classes no frame.
