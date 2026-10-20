import os
import unittest
from unittest.mock import patch

from ck_webhook import create_app

class MinimalRepository:
    pass

class WebhookTest(unittest.TestCase):
    def test_fabric_health_does_not_require_mcpi_connection(self):
        with patch.dict(os.environ,{"BLOCKCOMMANDER_WORLD_DRIVER":"fabric"}):
            app=create_app(repository=MinimalRepository(),migrate=False)
            response=app.test_client().get("/health")
        self.assertEqual(200,response.status_code)
        self.assertEqual("fabric",response.get_json()["world_driver"])

if __name__=="__main__": unittest.main()
