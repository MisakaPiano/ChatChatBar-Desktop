import importlib.util,sys,runpy
from pathlib import Path
p=Path(__file__).parent
s=importlib.util.spec_from_file_location("msi_safety",p/"sources/msi_safety.py");m=importlib.util.module_from_spec(s);s.loader.exec_module(m);sys.modules["msi_safety"]=m
sys.argv=["run_directory_guard_tests.py","--evidence",str(p),"--module","test_msi_shortcut_resource_graph"]
runpy.run_path("tools/desktop/windows/run_directory_guard_tests.py",run_name="__main__")
