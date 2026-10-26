"""pytest 根配置：确保 backend-python 目录在 sys.path 中（app 包可直接导入）。"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
