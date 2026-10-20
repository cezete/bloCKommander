import copy
import unittest
import uuid

from hangar.repository import HallConflict, HallForbidden
from hangar.stage import StageService

class FakeStageRepository:
    def __init__(self):
        self.fail_save=False
        self.types=[
            {"type_key":"START","display_name":"START","material_name":"LIME_GLAZED_TERRACOTTA","default_label":"START","default_description":"start","display_definition":{}},
            {"type_key":"STOP","display_name":"STOP","material_name":"RED_GLAZED_TERRACOTTA","default_label":"STOP","default_description":"stop","display_definition":{}},
        ]
        self.stage={"stage_id":str(uuid.uuid4()),"hall_id":str(uuid.uuid4()),"stage_name":"alpha stage",
            "owner_uuid":str(uuid.uuid4()),"world_uuid":str(uuid.uuid4()),"hall_min_x":10,"hall_min_y":64,"hall_min_z":20,
            "origin_rel_x":1,"origin_rel_y":1,"origin_rel_z":1,"size_x":5,"size_y":4,"size_z":5,"saved_version":0,"blocks":[]}
        self.saved=[]
    def ensure_stage(self,hall): return copy.deepcopy({k:v for k,v in self.stage.items() if k!="blocks"})
    def load_stage(self,stage_id):
        result=copy.deepcopy(self.stage);result["blocks"]=copy.deepcopy(self.saved);return result
    def load_stage_for_hall(self,hall_id): return self.load_stage(self.stage["stage_id"])
    def list_block_types(self): return copy.deepcopy(self.types)
    def save_stage_snapshot(self,stage_id,expected_version,blocks):
        if self.fail_save: raise RuntimeError("db unavailable")
        if expected_version!=self.stage["saved_version"]: raise RuntimeError("version conflict")
        self.saved=copy.deepcopy(blocks);self.stage["saved_version"]+=1;return self.stage["saved_version"]

class StageServiceTest(unittest.TestCase):
    def setUp(self):
        self.repo=FakeStageRepository();self.service=StageService(self.repo);self.stage_id=self.repo.stage["stage_id"]
        self.owner=self.repo.stage["owner_uuid"]
    def payload(self,**extra): return {"player_uuid":self.owner,"x":11,"y":65,"z":21,"orientation":"NORTH",**extra}

    def test_edits_do_not_change_canonical_snapshot_until_save(self):
        placed=self.service.place(self.stage_id,self.payload(type_key="START"));instance=placed["blocks"][0]["instance_id"]
        self.assertTrue(placed["dirty"]);self.assertEqual([],self.repo.saved)
        edited=self.service.update(self.stage_id,instance,{"player_uuid":self.owner,"label":"Indulás","description":"teszt"})
        self.assertEqual("Indulás",edited["blocks"][0]["label"]);self.assertEqual([],self.repo.saved)
        saved=self.service.save(self.stage_id,{"player_uuid":self.owner})
        self.assertFalse(saved["dirty"]);self.assertEqual(instance,str(self.repo.saved[0]["instance_id"]))

    def test_unauthorized_save_is_rejected_server_side(self):
        self.service.place(self.stage_id,self.payload(type_key="STOP"))
        with self.assertRaises(HallForbidden):
            self.service.save(self.stage_id,{"player_uuid":str(uuid.uuid4())})
        self.assertEqual([],self.repo.saved)

    def test_failed_save_keeps_dirty_workspace_and_old_snapshot(self):
        stage=self.service.place(self.stage_id,self.payload(type_key="START"));self.repo.fail_save=True
        with self.assertRaises(RuntimeError): self.service.save(self.stage_id,{"player_uuid":self.owner})
        current=self.service.view(self.service._workspace(self.stage_id))
        self.assertTrue(current["dirty"]);self.assertEqual(stage["blocks"],current["blocks"]);self.assertEqual([],self.repo.saved)

    def test_rebuild_requires_confirmation_and_restores_saved_ids(self):
        first=self.service.place(self.stage_id,self.payload(type_key="START"));instance=first["blocks"][0]["instance_id"]
        self.service.save(self.stage_id,{"player_uuid":self.owner})
        self.service.delete(self.stage_id,instance,{"player_uuid":self.owner})
        with self.assertRaises(HallConflict): self.service.rebuild(self.stage_id,{"player_uuid":self.owner})
        rebuilt=self.service.rebuild(self.stage_id,{"player_uuid":self.owner,"confirm_discard":True})
        self.assertEqual(instance,rebuilt["blocks"][0]["instance_id"]);self.assertFalse(rebuilt["dirty"])

    def test_empty_stage_can_be_saved(self):
        placed=self.service.place(self.stage_id,self.payload(type_key="STOP"));instance=placed["blocks"][0]["instance_id"]
        self.service.save(self.stage_id,{"player_uuid":self.owner})
        self.service.delete(self.stage_id,instance,{"player_uuid":self.owner})
        saved=self.service.save(self.stage_id,{"player_uuid":self.owner})
        self.assertEqual([],saved["blocks"]);self.assertEqual([],self.repo.saved)
        self.assertEqual([],self.service.rebuild(self.stage_id,{"player_uuid":self.owner})["blocks"])

if __name__=="__main__": unittest.main()
