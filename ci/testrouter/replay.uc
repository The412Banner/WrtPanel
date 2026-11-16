// WrtPanel test router: publishes procd/netifd ubus objects (system, service, network.*)
// with answers recorded read-only from the real router. Writes are accepted and logged only.
import * as ubus from 'ubus';
import * as uloop from 'uloop';
import { readfile } from 'fs';

let R = json(readfile('/ci/replay.json'));
uloop.init();
let conn = ubus.connect('/var/run/ubus/ubus.sock');
function fixed(v) { return { call: function(req) { return v; }, args: { ubus_rpc_session: '' } }; }
function info() {
	let i = R.system_info;
	i.localtime = time(); 
	return i;
}
conn.publish('system', {
	board: fixed(R.system_board),
	info: { call: function(req) { return info(); }, args: { ubus_rpc_session: '' } },
	reboot: { call: function(req) { warn('replay: system.reboot\n'); return {}; }, args: { ubus_rpc_session: '' } },
	validate_firmware_image: { call: function(req) { return { valid: true, forceable: true, allow_backup: true, tests: { fwtool_signature: true, fwtool_device_match: true } }; }, args: { path: '', ubus_rpc_session: '' } },
	sysupgrade: { call: function(req) { warn('replay: system.sysupgrade\n'); return {}; }, args: { path: '', force: false, backup: '', prefix: '', command: '', options: {}, ubus_rpc_session: '' } },
	watchdog: fixed({ status: 'running', timeout: 30, frequency: 5, magicclose: false })
});
conn.publish('service', {
	list: { call: function(req) {
		let n = req.args?.name;
		if (n) return R.service_list[n] ? { [n]: R.service_list[n] } : {};
		return R.service_list;
	}, args: { name: '', verbose: false, ubus_rpc_session: '' } },
	event: { call: function(req) { warn('replay: service.event ' + req.args?.type + '\n'); return {}; }, args: { type: '', data: {}, ubus_rpc_session: '' } },
	set: fixed({}), add: fixed({}), delete: fixed({}), signal: fixed({}),
	validate: fixed({}), get_data: fixed({}), state: fixed({}), watchdog: fixed({})
});
conn.publish('network', {
	get_proto_handlers: fixed(R.network_get_proto_handlers),
	reload: { call: function(req) { warn('replay: network.reload\n'); return {}; }, args: { ubus_rpc_session: '' } },
	restart: fixed({}), add_dynamic: fixed({})
});
conn.publish('network.interface', {
	dump: fixed(R.network_interface_dump)
});
conn.publish('network.device', {
	status: { call: function(req) {
		let n = req.args?.name;
		return n ? (R.network_device_status[n] ?? null) : R.network_device_status;
	}, args: { name: '', ubus_rpc_session: '' } }
});
for (let ifc in R.network_interface_dump.interface) {
	let name = ifc.interface;
	conn.publish('network.interface.' + name, {
		status: fixed(ifc),
		up: fixed({}), down: fixed({}), renew: fixed({}),
		dump: fixed(ifc)
	});
}
uloop.run();
