package orca.nodeagent2.exec;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import orca.nodeagent2.agentlib.Plugin;
import orca.nodeagent2.agentlib.PluginErrorCodes;
import orca.nodeagent2.agentlib.PluginException;
import orca.nodeagent2.agentlib.PluginReturn;
import orca.nodeagent2.agentlib.Properties;
import orca.nodeagent2.agentlib.ReservationId;
import orca.nodeagent2.agentlib.Util;

import org.apache.commons.logging.Log;

/**
 * This plugin executes external programs for each of the actions. 
 * @author ibaldin
 *
 */
public class Main implements Plugin {
	private static final String NEW_RESERVATIONID_PROP = "new.reservationid";

	private static final String ERROR_STRING = "ERROR";

	private static final String OK_STRING = "OK";

	Log log;
	
	public static final String WD_PROP="exec.wd";
	private String wd = null;
	
	enum ExecCmds {
		JOIN("exec.join"),
		LEAVE("exec.leave"),
		MODIFY("exec.modify"),
		RENEW("exec.renew"),
		STATUS("exec.status");
		
		String configProp;
		ExecCmds(String cp) {
			configProp = cp;
		}
		String getConfigProp() {
			return configProp;
		}
	}
	private Map<ExecCmds, List<String>> commands = new HashMap<ExecCmds, List<String>>();
	
	public void initialize(String config, Properties configProperties)
			throws PluginException {
		try {
			// this is how you get hold of the logger
			log = Util.getLog("exec-plugin");
			
			for(ExecCmds ec: ExecCmds.values()) {
				if (!configProperties.containsKey(ec.getConfigProp()) || 
						(configProperties.get(ec.getConfigProp()).length() == 0))
					throw new PluginException("Exec plugin missing configuration property " + ec.getConfigProp());
				commands.put(ec, Arrays.asList(configProperties.get(ec.getConfigProp()).split("\\s+")));
			}
			
			wd = configProperties.get(WD_PROP);
		} catch (Exception e) {
			throw new PluginException("Unable to initialize exec plugin: " + e);
		}
		log.info("Initializing plugin " + configProperties);
	}
	
	/**
	 * Convert from plugin properties to system environment properties
	 * (change to all caps, replace "." with "_")
	 * @param p
	 * @return
	 */
	private java.util.Properties convert(Properties p) {
		
		java.util.Properties retP = new java.util.Properties();
		
		for(Map.Entry<String, String> e: p.entrySet()) {
			retP.put(e.getKey().toUpperCase().replaceAll("\\.", "_"), e.getValue());
		}
		
		return retP;
	}
	
	/**
	 * Status is assumed to be <status string>; <reservation id>, where
	 * status string := <OK | ERROR> [status message]
	 * @param s
	 * @return
	 */
	private PluginErrorCodes getStatus(String s) throws Exception {
		if (s == null)
			throw new Exception("status string is null, unable to determine script return status");
		
		if (s.startsWith(OK_STRING))
			return PluginErrorCodes.OK;
		
		if (s.startsWith(ERROR_STRING))
			return PluginErrorCodes.EXCEPTION;
		
		throw new Exception("unable to parse script return status: " + s);
	}
	
	private String getStatusString(String s) throws Exception {
		// make sure it is valid
		PluginErrorCodes c = getStatus(s);
		int index = 0;
		if (c == PluginErrorCodes.OK)
			index = OK_STRING.length();
		if (c == PluginErrorCodes.EXCEPTION)
			index = ERROR_STRING.length();
		
		String[] statSplit = s.split(";");
		
		return statSplit[0].substring(index).trim();
	}
	
	private ReservationId getReservationId(String s) throws Exception {
		PluginErrorCodes c = getStatus(s);
		
		String[] statSplit = s.split(";");
		
		if (statSplit.length < 2)
			throw new Exception("script return status did not provide a reservation id: " + s);
		
		return new ReservationId(statSplit[1].trim());
	}
	
	// append reservation id as the last command-libe parameter
	private List<String> getCommand(List<String> c, ReservationId rid) {
		List<String> ret = new ArrayList(c);
		ret.add(rid.getId());
		return ret;

	}
	
	public PluginReturn join(Date until, Properties callerProperties)
			throws PluginException {
		
		java.util.Properties sep = convert(callerProperties);
		try {
			SystemExecutor se = new SystemExecutor(log);
		
			String status = se.execute(commands.get(ExecCmds.JOIN), sep, wd, (Reader)null);
		
			Properties retP = new Properties();
			
			if (getStatus(status) == PluginErrorCodes.EXCEPTION) 
				throw new Exception("error status: " + getStatusString(status));
			
			ReservationId rid = getReservationId(status);

			retP.putAll(callerProperties);
			
			return new PluginReturn(rid, retP);
		} catch (Exception e) {
			throw new PluginException("Unable to call join script " + commands.get(ExecCmds.JOIN) + " due to: " + e);
		}
	}

	/**
	 * Leave script is assumed to take reservation id as its last command-line parameter
	 */
	public PluginReturn leave(ReservationId resId, Properties callerProperties,
			Properties schedProperties) throws PluginException {
		
		java.util.Properties secp = convert(callerProperties);
		java.util.Properties sesp = convert(schedProperties);
		
		sesp.putAll(secp);
		
		try {
			SystemExecutor se = new SystemExecutor(log);
		
			ReservationId actual = resId;
			if (schedProperties.containsKey(NEW_RESERVATIONID_PROP))
				actual = new ReservationId(schedProperties.get(NEW_RESERVATIONID_PROP));
			
			String status = se.execute(getCommand(commands.get(ExecCmds.LEAVE), actual), sesp, wd, (Reader)null);
		
			Properties retP = new Properties();
			
			if (getStatus(status) == PluginErrorCodes.EXCEPTION) 
				throw new Exception("error status: " + getStatusString(status));
			
			ReservationId newRid = getReservationId(status);

			retP.putAll(callerProperties);
			retP.putAll(schedProperties);
			retP.put(NEW_RESERVATIONID_PROP, newRid.getId());
			
			return new PluginReturn(resId, retP);
		} catch (Exception e) {
			throw new PluginException("Unable to call leave script " + commands.get(ExecCmds.LEAVE) + " due to: " + e);
		}
	}

	/**
	 * Modify script is assumed to take reservation id as its last command-line parameter
	 */
	public PluginReturn modify(ReservationId resId,
			Properties callerProperties, Properties schedProperties)
			throws PluginException {
		
		java.util.Properties secp = convert(callerProperties);
		java.util.Properties sesp = convert(schedProperties);
		
		sesp.putAll(secp);
		
		try {
			SystemExecutor se = new SystemExecutor(log);
		
			ReservationId actual = resId;
			if (schedProperties.containsKey(NEW_RESERVATIONID_PROP))
				actual = new ReservationId(schedProperties.get(NEW_RESERVATIONID_PROP));
			
			String status = se.execute(getCommand(commands.get(ExecCmds.MODIFY), actual), sesp, wd, (Reader)null);
		
			Properties retP = new Properties();
			
			if (getStatus(status) == PluginErrorCodes.EXCEPTION) 
				throw new Exception("error status: " + getStatusString(status));
			
			ReservationId newRid = getReservationId(status);

			retP.putAll(callerProperties);
			retP.putAll(schedProperties);
			retP.put(NEW_RESERVATIONID_PROP, newRid.getId());
			
			return new PluginReturn(resId, retP);
		} catch (Exception e) {
			throw new PluginException("Unable to call modify script " + commands.get(ExecCmds.MODIFY) + " due to: " + e);
		}
	}

	/**
	 * Renew script is assumed to take reservation id as its last command-line parameter
	 */
	public PluginReturn renew(ReservationId resId, Date until,
			Properties joinProperties, Properties schedProperties)
			throws PluginException {
		
		java.util.Properties secp = convert(joinProperties);
		java.util.Properties sesp = convert(schedProperties);
		
		sesp.putAll(secp);
		
		try {
			SystemExecutor se = new SystemExecutor(log);
		
			ReservationId actual = resId;
			if (schedProperties.containsKey(NEW_RESERVATIONID_PROP))
				actual = new ReservationId(schedProperties.get(NEW_RESERVATIONID_PROP));
			
			String status = se.execute(getCommand(commands.get(ExecCmds.RENEW), actual), sesp, wd, (Reader)null);
		
			Properties retP = new Properties();
			
			if (getStatus(status) == PluginErrorCodes.EXCEPTION) 
				throw new Exception("error status: " + getStatusString(status));
			
			ReservationId newRid = getReservationId(status);

			retP.putAll(schedProperties);
			retP.put(NEW_RESERVATIONID_PROP, newRid.getId());
			
			return new PluginReturn(resId, retP);
		} catch (Exception e) {
			throw new PluginException("Unable to call leave script " + commands.get(ExecCmds.LEAVE) + " due to: " + e);
		}
	}

	/**
	 * Status script is assumed to take reservation id as its last command-line parameter
	 */
	public PluginReturn status(ReservationId resId, Properties schedProperties)
			throws PluginException {
		
		java.util.Properties sesp = convert(schedProperties);
		
		try {
			SystemExecutor se = new SystemExecutor(log);
		
			ReservationId actual = resId;
			if (schedProperties.containsKey(NEW_RESERVATIONID_PROP))
				actual = new ReservationId(schedProperties.get(NEW_RESERVATIONID_PROP));
			
			String status = se.execute(getCommand(commands.get(ExecCmds.STATUS), actual), sesp, wd, (Reader)null);
		
			Properties retP = new Properties();
			
			if (getStatus(status) == PluginErrorCodes.EXCEPTION) 
				throw new Exception("error status: " + getStatusString(status));
			
			retP.putAll(schedProperties);
			retP.put(NEW_RESERVATIONID_PROP, actual.getId());
			
			return new PluginReturn(resId, retP);
		} catch (Exception e) {
			throw new PluginException("Unable to call modify script " + commands.get(ExecCmds.MODIFY) + " due to: " + e);
		}
	}
	
/*	public static void main(String[] argv) {
		Properties p = new Properties();
		
		p.put("exec.join", "join.sh");
		p.put("exec.leave", "leave.sh -s");
		p.put("exec.modify", "modify.sh -a   	-h");
		p.put("exec.renew", "renew.sh -g ");
		
		Main m = new Main();
		try {
			m.initialize(null, p);
		} catch (PluginException pe) {
			System.err.println(pe);
		}
		
		for(Map.Entry<ExecCmds, List<String>> e: m.commands.entrySet()) {
			System.out.println(e.getKey());
			for(String s: e.getValue()) {
				System.out.print("[" + s + "] ");
			}
			System.out.println();
		}
		
		java.util.Properties up = m.convert(p);
		for(Map.Entry<?, ?> e: up.entrySet()) {
			System.out.println(e.getKey() + " ---> " + e.getValue());
		}
	}*/

}
