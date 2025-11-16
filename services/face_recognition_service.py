# services/face_recognition_service.py
"""
Face recognition service leve para detectar rostos na câmera e desenhar overlays.
Implementa:
 - EmbeddingIndex (KD-Tree se sklearn estiver disponível)
 - FaceDetector (MediaPipe se disponível, fallback HaarCascade)
 - FaceRecognitionService: loop de captura, tracker KCF, reconhecimento sob demanda

Uso: instanciar FaceRecognitionService(on_overlay=callback) e chamar initialize_camera/start_async_processing
O callback on_overlay(frame_bgr, boxes) deve receber o frame e lista de tuplas (x1,y1,x2,y2,label,score)
"""

from typing import List, Tuple, Optional, Any
import os
import time
import pickle
import threading
import queue
import math
from collections import deque

import cv2
import numpy as np
import face_recognition

try:
    import mediapipe as mp
    _HAS_MEDIAPIPE = True
except Exception:
    _HAS_MEDIAPIPE = False

try:
    from sklearn.neighbors import NearestNeighbors
    _HAS_SKLEARN = True
except Exception:
    _HAS_SKLEARN = False


class EmbeddingIndex:
    """Índice simples para busca do vizinho mais próximo."""
    def __init__(self, encodings: List[np.ndarray], names: List[str]):
        self.names = names or []
        self.data = np.asarray(encodings, dtype=np.float32) if encodings else np.empty((0, 128), dtype=np.float32)
        self.nn = None
        if _HAS_SKLEARN and self.data.shape[0] >= 5:
            self.nn = NearestNeighbors(n_neighbors=1, algorithm="kd_tree", metric="euclidean")
            self.nn.fit(self.data)

    def query(self, vec: np.ndarray) -> Tuple[int, float]:
        if self.data.shape[0] == 0:
            return -1, float('inf')
        v = np.asarray(vec, dtype=np.float32).reshape(1, -1)
        if self.nn is not None:
            dists, idxs = self.nn.kneighbors(v, n_neighbors=1, return_distance=True)
            return int(idxs[0, 0]), float(dists[0, 0])
        dists = np.linalg.norm(self.data - v, axis=1)
        best_idx = int(np.argmin(dists))
        return best_idx, float(dists[best_idx])


class FaceDetector:
    def __init__(self, min_conf: float = 0.6, model_selection: int = 1):
        self.use_mediapipe = _HAS_MEDIAPIPE
        if self.use_mediapipe:
            self.mp_face = mp.solutions.face_detection.FaceDetection(
                model_selection=model_selection, min_detection_confidence=min_conf
            )
        else:
            self.cascade = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')

    def detect(self, frame_bgr: np.ndarray) -> List[Tuple[int, int, int, int, float]]:
        """Retorna lista de (x1,y1,x2,y2,score) em coordenadas do frame dado."""
        if self.use_mediapipe:
            rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
            res = self.mp_face.process(rgb)
            out = []
            if getattr(res, 'detections', None):
                h, w = frame_bgr.shape[:2]
                for d in res.detections:
                    bb = d.location_data.relative_bounding_box
                    x1 = max(0, int(bb.xmin * w))
                    y1 = max(0, int(bb.ymin * h))
                    x2 = min(w, int((bb.xmin + bb.width) * w))
                    y2 = min(h, int((bb.ymin + bb.height) * h))
                    score = float(d.score[0]) if getattr(d, 'score', None) else 0.0
                    out.append((x1, y1, x2, y2, score))
            return out
        gray = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
        faces = self.cascade.detectMultiScale(gray, scaleFactor=1.2, minNeighbors=3, minSize=(60, 60), flags=cv2.CASCADE_SCALE_IMAGE)
        out = []
        for (x, y, w, h) in faces:
            out.append((int(x), int(y), int(x + w), int(y + h), 0.8))
        return out


class FaceRecognitionService:
    """Serviço principal para detectar, trackear e reconhecer rostos.

    Parâmetros:
      encodings_file: caminho para arquivo pickle com {'encodings': [...], 'names': [...]}
      on_overlay: callback(frame_bgr, overlays) onde overlays é lista de (x1,y1,x2,y2,label,score)
    """

    def __init__(
        self,
        encodings_file: str = 'encodings.pickle',
        on_overlay: Optional[Any] = None,
        capture_fps: int = 15,
        display_fps: int = 15,
        video_scale_factor: float = 0.5,
    ):
        self.encodings_file = encodings_file
        self.on_overlay = on_overlay

        # thresholds e parâmetros
        self.recognition_threshold = 0.55
        self.user_exit_frames = 12
        self.pose_stride = 2
        self.frontality_thr = 35.0
        self.min_face_area_rel = 0.05

        # threads/queues
        self.processing = False
        self.capture_thread: Optional[threading.Thread] = None
        self.recognition_thread: Optional[threading.Thread] = None
        self.recognition_queue: "queue.Queue[np.ndarray]" = queue.Queue(maxsize=1)

        # state
        self.tracker = None
        self.track_ok_frames = 0
        self.no_face_frames = 0
        self.pose_buffer = deque(maxlen=12)
        self.recognition_in_progress = False
        self.result_displayed = False
        self.lockout_active = False
        self.lockout_start_time = 0.0

        self.video_capture: Optional[cv2.VideoCapture] = None
        self.frame_count = 0
        self.capture_fps = capture_fps
        self.display_fps = display_fps
        self.video_scale_factor = video_scale_factor

        # detector + encodings
        self.detector = FaceDetector(min_conf=0.6)
        self.known_encodings: List[np.ndarray] = []
        self.known_names: List[str] = []
        self.index: Optional[EmbeddingIndex] = None

        # load encodings if present
        self.load_encodings()

    # -------------------- encodings --------------------
    def load_encodings(self) -> bool:
        if not os.path.exists(self.encodings_file):
            return False
        try:
            with open(self.encodings_file, 'rb') as f:
                data = pickle.load(f)
            self.known_encodings = data.get('encodings', [])
            self.known_names = data.get('names', [])
            self.index = EmbeddingIndex(self.known_encodings, self.known_names)
            return True
        except Exception as e:
            print('Erro ao carregar encodings:', e)
            return False

    def update_encodings(self, encodings_data: bytes) -> bool:
        try:
            with open(self.encodings_file, 'wb') as f:
                f.write(encodings_data)
            return self.load_encodings()
        except Exception as e:
            print('Erro ao atualizar encodings:', e)
            return False

    # -------------------- camera --------------------
    def initialize_camera(self, camera_index: int = 0, width: int = 640, height: int = 480) -> bool:
        cap = cv2.VideoCapture(camera_index)
        if not cap.isOpened():
            # tenta outros índices
            for i in range(1, 5):
                cap = cv2.VideoCapture(i)
                if cap.isOpened():
                    camera_index = i
                    break
        if not cap.isOpened():
            raise RuntimeError('Nenhuma câmera disponível')
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        cap.set(cv2.CAP_PROP_FPS, self.capture_fps)
        self.video_capture = cap
        return True

    def cleanup_camera(self) -> None:
        self.processing = False
        if self.capture_thread and self.capture_thread.is_alive():
            self.capture_thread.join(timeout=1.0)
        if self.recognition_thread and self.recognition_thread.is_alive():
            self.recognition_thread.join(timeout=1.0)
        if self.video_capture:
            try:
                self.video_capture.release()
            except Exception:
                pass
        cv2.destroyAllWindows()

    # -------------------- tracker --------------------
    def _start_tracker(self, frame: np.ndarray, box_xyxy: Tuple[int, int, int, int]):
        x1, y1, x2, y2 = box_xyxy
        w = max(2, x2 - x1)
        h = max(2, y2 - y1)
        # compatibilidade com versões do OpenCV
        try:
            self.tracker = cv2.TrackerKCF_create()
        except Exception:
            self.tracker = cv2.TrackerKCF.create()
        self.tracker.init(frame, (x1, y1, w, h))
        self.track_ok_frames = 0

    def _stop_tracker(self):
        self.tracker = None
        self.track_ok_frames = 0

    # -------------------- main loops --------------------
    def start_async_processing(self) -> None:
        if not self.video_capture or not self.video_capture.isOpened():
            raise RuntimeError('Camera não inicializada')
        if self.processing:
            return
        self.processing = True
        # limpar fila
        while not self.recognition_queue.empty():
            try:
                self.recognition_queue.get_nowait()
            except queue.Empty:
                break
        self.capture_thread = threading.Thread(target=self._capture_and_display_frames, daemon=True)
        self.recognition_thread = threading.Thread(target=self._process_recognition, daemon=True)
        self.capture_thread.start()
        self.recognition_thread.start()

    def stop_async_processing(self) -> None:
        self.processing = False
        if self.capture_thread and self.capture_thread.is_alive():
            self.capture_thread.join(timeout=1.0)
        if self.recognition_thread and self.recognition_thread.is_alive():
            self.recognition_thread.join(timeout=1.0)

    def _capture_and_display_frames(self) -> None:
        frame_interval = max(1e-3, 1.0 / max(1, self.capture_fps))
        last_capture = 0.0
        scale = self.video_scale_factor
        while self.processing and self.video_capture and self.video_capture.isOpened():
            now = time.time()
            if now - last_capture < frame_interval:
                time.sleep(0.001)
                continue
            ok, frame = self.video_capture.read()
            if not ok:
                print('Falha ao capturar frame')
                break
            last_capture = now
            self.frame_count += 1

            # overlay por callback antes de mostrar
            overlays: List[Tuple[int, int, int, int, str, float]] = []

            # se existe tracker, atualiza
            if self.tracker is not None:
                ok_tracker, bbox = self.tracker.update(frame)
                if ok_tracker:
                    self.track_ok_frames += 1
                    x, y, w, h = [int(v) for v in bbox]
                    x1 = max(0, x); y1 = max(0, y); x2 = min(frame.shape[1], x + w); y2 = min(frame.shape[0], y + h)
                    face_crop = frame[y1:y2, x1:x2].copy()
                    # amostra pose e push para reconhecimento quando apropriado
                    if (self.frame_count % self.pose_stride) == 0:
                        ang = self._estimate_head_pose_angles(face_crop)
                        if ang:
                            yaw, pitch, roll = ang
                            area_rel = ((x2-x1)*(y2-y1)) / float(frame.shape[0]*frame.shape[1])
                            score = abs(yaw) + 0.8*abs(pitch) + 0.3*abs(roll)
                            self.pose_buffer.append((score, -area_rel, face_crop, (yaw,pitch,roll)))
                    if (not self.recognition_in_progress and not self.result_displayed
                        and self.recognition_queue.empty() and len(self.pose_buffer) >= 3):
                        best = min(self.pose_buffer, key=lambda t: (t[0], t[1]))
                        best_score, neg_area, best_crop, best_ang = best
                        if best_score < self.frontality_thr and (-neg_area) >= self.min_face_area_rel:
                            try:
                                self.recognition_queue.put_nowait(best_crop)
                                self.recognition_in_progress = True
                                self.pose_buffer.clear()
                            except queue.Full:
                                pass
                    # adicionar overlay temporário
                    overlays.append((x1, y1, x2, y2, 'Rostro', 0.0))
                else:
                    # tracker perdeu alvo
                    self._stop_tracker()
                    self.pose_buffer.clear()
                    self.no_face_frames = 0
            else:
                # sem tracker: detecção em frame reduzido
                small = cv2.resize(frame, None, fx=scale, fy=scale)
                faces = self.detector.detect(small)
                if faces:
                    faces.sort(key=lambda b: (b[2]-b[0])*(b[3]-b[1]), reverse=True)
                    x1s, y1s, x2s, y2s, score = faces[0]
                    x1 = int(x1s / scale); y1 = int(y1s / scale); x2 = int(x2s / scale); y2 = int(y2s / scale)
                    area_rel = ((x2-x1)*(y2-y1)) / float(frame.shape[0]*frame.shape[1])
                    if area_rel >= self.min_face_area_rel:
                        self._start_tracker(frame, (x1, y1, x2, y2))
                        self.pose_buffer.clear()
                        overlays.append((x1, y1, x2, y2, 'Rostro', 0.0))

            # se houver callback de overlay, desenha e envia
            if self.on_overlay:
                try:
                    self.on_overlay(frame, overlays)
                except Exception:
                    pass

            # controle de exibição local (opcional): pode ser usado em run_recognition_loop
            # para simplicidade, apenas armazena último frame
            self._last_frame = frame

        # fim loop

    def _process_recognition(self) -> None:
        while self.processing:
            try:
                face_crop = self.recognition_queue.get(timeout=0.1)
            except queue.Empty:
                continue
            try:
                name, confidence = self._recognize_face_crop(face_crop)
                self.recognition_in_progress = False
                self.result_displayed = True
                # quando houver reconhecimento, pode-se chamar callback com label
                label = name if name != 'Desconhecido' else 'Desconhecido'
                # desenhar resultado sobre _last_frame centralizado no rosto (está simplificado)
                # liberar tracker até pessoa sair
                self._stop_tracker()
                # lockout
                self.lockout_active = True
                self.lockout_start_time = time.time()
                # Delay para simular exibição
                time.sleep(1.0)
                self.result_displayed = False
                self.lockout_active = False
            except Exception as e:
                print('Erro no reconhecimento:', e)
                self.recognition_in_progress = False

    # -------------------- reconhecimento interno --------------------
    def _recognize_face_crop(self, face_bgr: np.ndarray) -> Tuple[str, float]:
        if face_bgr.size == 0:
            return ('Desconhecido', 0.0)
        h, w = face_bgr.shape[:2]
        scale_up = 1.0 if max(h, w) >= 180 else 180.0 / max(1, max(h, w))
        if scale_up > 1.0:
            face_bgr = cv2.resize(face_bgr, None, fx=scale_up, fy=scale_up)
        rgb = cv2.cvtColor(face_bgr, cv2.COLOR_BGR2RGB)
        locs = face_recognition.face_locations(rgb, model='hog')
        if not locs:
            return ('Desconhecido', 0.0)
        encs = face_recognition.face_encodings(rgb, [locs[0]])
        if not encs:
            return ('Desconhecido', 0.0)
        vec = encs[0]
        if not self.index or len(self.known_encodings) == 0:
            return ('Desconhecido', 0.0)
        best_idx, dist = self.index.query(vec)
        if best_idx < 0:
            return ('Desconhecido', 0.0)
        confidence = max(0.0, 1.0 - float(dist))
        if dist < float(self.recognition_threshold):
            return (self.known_names[best_idx], confidence)
        return ('Desconhecido', confidence)

    # -------------------- util --------------------
    def _estimate_head_pose_angles(self, face_bgr: np.ndarray) -> Optional[Tuple[float, float, float]]:
        # Implementação simplificada: usa landmarks do face_recognition
        try:
            rgb = cv2.cvtColor(face_bgr, cv2.COLOR_BGR2RGB)
            lms_list = face_recognition.face_landmarks(rgb, model='large')
            if not lms_list:
                return None
            lms = lms_list[0]
            le = lms.get('left_eye', []); re = lms.get('right_eye', [])
            if len(le) < 2 or len(re) < 2:
                return None
            left_eye_outer = min(le, key=lambda p: p[0])
            right_eye_outer = max(re, key=lambda p: p[0])
            nose = lms.get('nose_tip', [])
            if not nose:
                return None
            nose_tip = nose[len(nose)//2]
            chin = lms.get('chin', [])
            if len(chin) < 9:
                return None
            chin_center = chin[8]
            top_lip = lms.get('top_lip', []); bottom_lip = lms.get('bottom_lip', [])
            mouth_pts = (top_lip or []) + (bottom_lip or [])
            if not mouth_pts:
                return None
            left_mouth = min(mouth_pts, key=lambda p: p[0])
            right_mouth = max(mouth_pts, key=lambda p: p[0])
            image_points = np.array([left_eye_outer, right_eye_outer, nose_tip, chin_center, left_mouth, right_mouth], dtype=np.float64)
            model_points = np.array([
                (-225.0, 170.0, -135.0),
                (225.0, 170.0, -135.0),
                (0.0, 0.0, 0.0),
                (0.0, -330.0, -65.0),
                (-150.0, -150.0, -125.0),
                (150.0, -150.0, -125.0)
            ], dtype=np.float64)
            h, w = face_bgr.shape[:2]
            f = float(w)
            C = np.array([[f, 0, w/2.0], [0, f, h/2.0], [0, 0, 1.0]], dtype=np.float64)
            dist = np.zeros((4,1), dtype=np.float64)
            ok, rvec, tvec = cv2.solvePnP(model_points, image_points, C, dist, flags=cv2.SOLVEPNP_ITERATIVE)
            if not ok:
                return None
            R, _ = cv2.Rodrigues(rvec)
            sy = math.sqrt(R[0,0]*R[0,0] + R[1,0]*R[1,0])
            singular = sy < 1e-6
            if not singular:
                pitch = math.degrees(math.atan2(R[2,1], R[2,2]))
                yaw   = math.degrees(math.atan2(-R[2,0], sy))
                roll  = math.degrees(math.atan2(R[1,0], R[0,0]))
            else:
                pitch = math.degrees(math.atan2(-R[1,2], R[1,1]))
                yaw   = math.degrees(math.atan2(-R[2,0], sy))
                roll  = 0.0
            def norm180(a): return (a + 180.0) % 360.0 - 180.0
            yaw, pitch, roll = norm180(yaw), norm180(pitch), norm180(roll)
            if abs(pitch) > 90.0:
                pitch = (180.0 - abs(pitch)) * (1 if pitch > 0 else -1)
            return (yaw, pitch, roll)
        except Exception:
            return None

    # -------------------- API pública --------------------
    def run_recognition_loop(self, show_video: bool = True) -> None:
        if not self.video_capture:
            raise RuntimeError('Camera não inicializada')
        self.start_async_processing()
        try:
            display_dt = 1.0 / max(1, self.display_fps)
            last = 0.0
            while True:
                now = time.time()
                if now - last < display_dt:
                    time.sleep(0.001)
                    continue
                # obter último frame se existir
                frame = getattr(self, '_last_frame', None)
                if frame is None:
                    continue
                # desenhar overlays simples antes de mostrar
                # se on_overlay não desenha, mostra frame cru
                if self.on_overlay is None:
                    cv2.imshow('FaceRecognition', frame)
                else:
                    # assume que callback já desenhou sobre o frame
                    cv2.imshow('FaceRecognition', frame)
                key = cv2.waitKey(1) & 0xFF
                if key == 27:
                    break
                last = now
        except KeyboardInterrupt:
            pass
        finally:
            self.stop_async_processing()
            self.cleanup_camera()

    def process_single_frame(self, frame: np.ndarray) -> Tuple[str, float]:
        return self._recognize_face_crop(frame)

    def get_recognition_stats(self) -> dict:
        return {
            'known_people': len(self.known_names),
            'recognition_threshold': self.recognition_threshold,
            'display_fps': self.display_fps,
            'capture_fps': self.capture_fps,
            'tracker_active': self.tracker is not None,
        }


# -------------------- util helper para overlay padrão --------------------
def default_overlay_draw(frame_bgr: np.ndarray, boxes: List[Tuple[int,int,int,int,str,float]]) -> None:
    """Desenha overlays translucidos sobre frame_bgr in-place."""
    for (x1, y1, x2, y2, label, score) in boxes:
        # fundo translúcido
        overlay = frame_bgr.copy()
        alpha = 0.25
        cv2.rectangle(overlay, (x1, y1), (x2, y2), (34, 139, 34), -1)
        cv2.addWeighted(overlay, alpha, frame_bgr, 1 - alpha, 0, frame_bgr)
        # contorno
        cv2.rectangle(frame_bgr, (x1, y1), (x2, y2), (34, 139, 34), 2)
        # label
        text = f"{label}" if score == 0.0 else f"{label} ({score:.2f})"
        cv2.putText(frame_bgr, text, (x1, max(16, y1 - 8)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255,255,255), 2)


if __name__ == '__main__':
    # Exemplo de uso simples
    def on_overlay(frame, overlays):
        default_overlay_draw(frame, overlays)

    svc = FaceRecognitionService(on_overlay=on_overlay)
    svc.initialize_camera(0, width=800, height=600)
    svc.run_recognition_loop(show_video=True)

