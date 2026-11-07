"""Response-only projection. Never write a Hall into the shared Stage header."""


def project_stage(view, header, hall):
    origin={axis: int(header[f"origin_rel_{axis}"]) + int(hall[f"min_{axis}"])
            for axis in ("x","y","z")}
    result={**view,"hall_id":str(hall["hall_id"]),"world_uuid":str(hall["world_uuid"]),
            "origin":origin,
            "size":{axis:int(header[f"size_{axis}"]) for axis in ("x","y","z")}}
    # Legacy block DTOs carry absolute x/y/z as well as relative coordinates.
    if "blocks" in view and "domain_layout" not in view:
        result["blocks"]=[{**block,**{axis:origin[axis]+int(block[f"rel_{axis}"])
            for axis in ("x","y","z")}} for block in view["blocks"]]
    return result
