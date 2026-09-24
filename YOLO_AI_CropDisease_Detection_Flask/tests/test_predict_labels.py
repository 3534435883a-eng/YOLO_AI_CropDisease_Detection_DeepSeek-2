import sys
import types
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


fake_ultralytics = types.ModuleType("ultralytics")
fake_ultralytics.YOLO = lambda path: None
with patch.dict(sys.modules, {"ultralytics": fake_ultralytics}):
    from predict.predictImg import ImagePredictor


class PredictLabelsTest(unittest.TestCase):
    def test_labels_follow_weight_metadata(self):
        class Model:
            names = {0: "Early_Blight(早疫病)", 1: "Healthy(健康)"}

        with patch.dict(ImagePredictor.__init__.__globals__, {"YOLO": lambda path: Model()}):
            predictor = ImagePredictor("tomato_best.pt", "image.jpg", "tomato")

        self.assertEqual(["Early_Blight(早疫病)", "Healthy(健康)"], predictor.labels)


if __name__ == "__main__":
    unittest.main()
