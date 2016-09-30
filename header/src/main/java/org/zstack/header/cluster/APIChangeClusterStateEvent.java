package org.zstack.header.cluster;

import org.zstack.header.message.APIEvent;

/**
 *@apiResult
 *
 * api event for message :ref:`APIChangeClusterStateMsg`
 *
 *@since 0.1.0
 *
 *@example
 *
 * {
"org.zstack.header.cluster.APIChangeClusterStateEvent": {
"inventory": {
"name": "cluster1",
"uuid": "44e981a73c7d414a995d5894b086670a",
"description": "Test",
"state": "Enabled",
"hypervisorType": "KVM",
"createDate": "Apr 28, 2014 6:48:15 PM",
"lastOpDate": "Apr 28, 2014 6:48:15 PM",
"zoneUuid": "def3040c73404a6f9abeba7f720748cc",
"type": "zstack"
},
"success": true
}
}
 */
public class APIChangeClusterStateEvent extends APIEvent {
    /**
     * @desc cluster inventory (see :ref:`ClusterInventory`)
     */
	private ClusterInventory inventory;

	public APIChangeClusterStateEvent() {
		super(null);
	}
	
	public APIChangeClusterStateEvent(String apiId) {
	    super(apiId);
    }

	public ClusterInventory getInventory() {
    	return inventory;
    }

	public void setInventory(ClusterInventory inventory) {
    	this.inventory = inventory;
    }
}
