/*
 * Copyright 2018 Karlsruhe Institute of Technology.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.kit.datamanager.entities.messaging;

import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author jejkal
 */
public class DataResourceMessageTest{

  @Test
  public void testFactoryDataResourceMessage(){
    for(DataResourceMessage.ACTION action : DataResourceMessage.ACTION.values()){
      performFactoryDataResourceMessageTest(action);
    }
  }

  @Test
  public void testFactoryAclMessage(){
    DataResourceMessage msg = DataResourceMessage.factoryUpdateAclMessage("1", "tester", "localhost");
    Assert.assertEquals("dataresource", msg.getEntityName());
    Assert.assertEquals("tester", msg.getPrincipal());
    Assert.assertEquals("localhost", msg.getSender());
    Assert.assertNotNull(msg.getTimestamp());

    Assert.assertEquals("1", msg.getEntityId());
    Assert.assertEquals(DataResourceMessage.ACTION.UPDATE.getValue(), msg.getAction());
    Assert.assertEquals(DataResourceMessage.SUB_CATEGORY.ACL.getValue(), msg.getSubCategory());

    Assert.assertEquals("dataresource." + DataResourceMessage.ACTION.UPDATE.getValue() + "." + DataResourceMessage.SUB_CATEGORY.ACL.getValue(), msg.getRoutingKey());
  }

  @Test
  public void testFactoryDataMessage(){
    for(DataResourceMessage.ACTION action : new DataResourceMessage.ACTION[]{DataResourceMessage.ACTION.CREATE, DataResourceMessage.ACTION.UPDATE, DataResourceMessage.ACTION.DELETE}){
      performFactoryDataMessageTest(action, "path/file.txt", "plain/txt", "file:///tmp/myFile.txt");
    }
  }

  /**
   * Helper to create data resource messages for each action.
   */
  private void performFactoryDataResourceMessageTest(DataResourceMessage.ACTION action){

    DataResourceMessage msg;

    switch(action){
      case UPDATE:
        msg = DataResourceMessage.factoryUpdateMessage("1", "tester", "localhost");
        break;
      case DELETE:
        msg = DataResourceMessage.factoryDeleteMessage("1", "tester", "localhost");
        break;
      case FIX:
        msg = DataResourceMessage.factoryFixMessage("1", "tester", "localhost");
        break;
      case REVOKE:
        msg = DataResourceMessage.factoryRevokeMessage("1", "tester", "localhost");
        break;
      default:
        msg = DataResourceMessage.factoryCreateMessage("1", "tester", "localhost");
        break;
    }
    Assert.assertEquals("dataresource", msg.getEntityName());
    Assert.assertEquals("tester", msg.getPrincipal());
    Assert.assertEquals("localhost", msg.getSender());
    Assert.assertNotNull(msg.getTimestamp());

    Assert.assertEquals("1", msg.getEntityId());
    Assert.assertEquals(action.getValue(), msg.getAction());

    //test routing key creation and lowercase entity name
    Assert.assertEquals("dataresource." + action.getValue(), msg.getRoutingKey());
  }

  /**
   * Helper to create data resource messages with message sub type DATA for each
   * action.
   */
  private void performFactoryDataMessageTest(DataResourceMessage.ACTION action, String contentPath, String contentUri, String contentType){

    DataResourceMessage msg;

    switch(action){
      case UPDATE:
        msg = DataResourceMessage.factoryUpdateDataMessage("1", contentPath, contentUri, contentType, "tester", "localhost");
        break;
      case DELETE:
        msg = DataResourceMessage.factoryDeleteDataMessage("1", contentPath, contentUri, contentType, "tester", "localhost");
        break;
      default:
        msg = DataResourceMessage.factoryCreateDataMessage("1", contentPath, contentUri, contentType, "tester", "localhost");
        break;
    }
    Assert.assertEquals("dataresource", msg.getEntityName());
    Assert.assertEquals("tester", msg.getPrincipal());
    Assert.assertEquals("localhost", msg.getSender());
    Assert.assertNotNull(msg.getTimestamp());

    Assert.assertEquals("1", msg.getEntityId());
    Assert.assertEquals(action.getValue(), msg.getAction());
    Assert.assertEquals(3, msg.getMetadata().size());

    Assert.assertTrue(msg.getMetadata().containsKey(DataResourceMessage.CONTENT_PATH_PROPERTY));
    Assert.assertTrue(msg.getMetadata().containsKey(DataResourceMessage.CONTENT_TYPE_PROPERTY));
    Assert.assertTrue(msg.getMetadata().containsKey(DataResourceMessage.CONTENT_URI_PROPERTY));

    Assert.assertEquals(msg.getMetadata().get(DataResourceMessage.CONTENT_PATH_PROPERTY), contentPath);
    Assert.assertEquals(msg.getMetadata().get(DataResourceMessage.CONTENT_TYPE_PROPERTY), contentType);
    Assert.assertEquals(msg.getMetadata().get(DataResourceMessage.CONTENT_URI_PROPERTY), contentUri);

    Assert.assertEquals(DataResourceMessage.SUB_CATEGORY.DATA.getValue(), msg.getSubCategory());

    //test routing key creation and lowercase entity name
    Assert.assertEquals("dataresource." + action.getValue() + "." + DataResourceMessage.SUB_CATEGORY.DATA.getValue(), msg.getRoutingKey());
  }
}
