import copy, unittest, uuid
from hangar.builder import HallValidationError
from hangar.repository import HallForbidden
from hangar.service import HallService

class FakeMinecraft:
    def __init__(self): self.calls=[]
    def setBlocks(self,*args): self.calls.append(("setBlocks",args))
    def setBlock(self,*args): self.calls.append(("setBlock",args))
    def setSign(self,*args): self.calls.append(("setSign",args))

class FakeRepository:
    def __init__(self): self.halls={}
    def reserve(self,h,request_id): h=copy.deepcopy(h);h["lifecycle"]="BUILDING";self.halls[h["hall_id"]]=h;return h
    def state(self,hall_id,state,error=None): self.halls[hall_id]["lifecycle"]=state
    def get(self,hall_id): return self.halls[hall_id]
    def get_by_code(self,owner_uuid,hall_code): return next(h for h in self.halls.values() if h["owner_uuid"]==owner_uuid and h["hall_code"].lower()==hall_code.lower())
    def restore_candidates(self,owner_uuid,world): return [h for h in self.halls.values() if h["owner_uuid"]==owner_uuid and h["world_name"]==world["world_name"] and h["dimension_key"]==world["dimension"] and h["world_uuid"]!=world["world_uuid"]]
    def by_button(self,world_uuid,x,y,z):
        return next(h for h in self.halls.values() if h["world_uuid"]==world_uuid and any(c["kind"]=="DESTROY_BUTTON" and (c["x"],c["y"],c["z"])==(x,y,z) for c in h["controls"]))
    def delete(self,hall_id): del self.halls[hall_id]
    def reassign_world(self,hall_id,owner_uuid,world):
        h=self.halls[hall_id];h.update(world);h["dimension_key"]=world["dimension"];return h

class HallServiceTest(unittest.TestCase):
    def setUp(self):
        self.owner=str(uuid.uuid4());self.world=str(uuid.uuid4());self.repo=FakeRepository();self.mc=FakeMinecraft();self.service=HallService(self.repo,self.mc)
        self.payload={"request_id":str(uuid.uuid4()),"player_uuid":self.owner,"player_name":"Tester","world_uuid":self.world,"world_name":"world","dimension":"minecraft:overworld","orientation":"SOUTH","material":"STONE","material_id":1,"hall_code":"alpha","p1":{"x":0,"y":10,"z":0},"p2":{"x":6,"y":16,"z":6}}
    def test_create_rebuild_and_owner_destroy(self):
        hall=self.service.create(self.payload);self.assertEqual("ACTIVE",hall["lifecycle"])
        self.assertEqual(6,hall["occupied_max_x"])
        self.service.rebuild(hall["hall_id"],self.payload);self.assertEqual(1,len(self.repo.halls))
        self.service.rebuild_by_code({**self.payload,"hall_code":"ALPHA"});self.assertEqual(1,len(self.repo.halls))
        button=next(c for c in hall["controls"] if c["kind"]=="DESTROY_BUTTON")
        with self.assertRaises(HallForbidden): self.service.destroy({"world_uuid":self.world,"player_uuid":str(uuid.uuid4()),**{k:button[k] for k in ("x","y","z")}})
        self.service.destroy({"world_uuid":self.world,"player_uuid":self.owner,**{k:button[k] for k in ("x","y","z")}});self.assertFalse(self.repo.halls)
    def test_rebuild_rejects_different_world_without_restore(self):
        hall=self.service.create(self.payload);other={**self.payload,"world_uuid":str(uuid.uuid4()),"world_name":"replacement"}
        with self.assertRaises(HallForbidden): self.service.rebuild(hall["hall_id"],other)
        rebuilt=self.service.rebuild(hall["hall_id"],other,restore=True);self.assertEqual(other["world_uuid"],rebuilt["world_uuid"])
    def test_parameterless_restore_detects_replaced_same_logical_world(self):
        hall=self.service.create(self.payload);replacement={**self.payload,"world_uuid":str(uuid.uuid4())}
        restored=self.service.restore_all(replacement)
        self.assertEqual(1,len(restored));self.assertEqual(replacement["world_uuid"],hall["world_uuid"])
        self.assertEqual([],self.service.restore_all(replacement))

    def test_fabric_driver_updates_database_without_mcpi_world_writes(self):
        service=HallService(self.repo,self.mc,apply_world=False)
        hall=service.create(self.payload)
        self.assertEqual("ACTIVE",hall["lifecycle"]);self.assertEqual([],self.mc.calls)
        button=next(c for c in hall["controls"] if c["kind"]=="DESTROY_BUTTON")
        service.destroy({"world_uuid":self.world,"player_uuid":self.owner,**{k:button[k] for k in ("x","y","z")}})
        self.assertEqual([],self.mc.calls);self.assertFalse(self.repo.halls)

    def test_volume_limit_rejects_accidental_large_world_edits(self):
        payload={**self.payload,"p2":{"x":100,"y":100,"z":100}}
        with self.assertRaises(HallValidationError): self.service.create(payload)

    def test_requested_interior_height_ignores_second_vertical_coordinate(self):
        payload={**self.payload,"interior_height":7,"material":"minecraft:bricks",
                 "p1":{"x":0,"y":10,"z":0},"p2":{"x":6,"y":-50,"z":6}}
        hall=self.service.create(payload)
        self.assertEqual(10,hall["min_y"])
        self.assertEqual(18,hall["max_y"])
        self.assertEqual(7,hall["max_y"]-hall["min_y"]-1)
        self.assertEqual("minecraft:bricks",hall["material_name"])

    def test_requested_interior_height_is_validated(self):
        with self.assertRaises(HallValidationError):
            self.service.create({**self.payload,"interior_height":2})

if __name__=="__main__": unittest.main()
