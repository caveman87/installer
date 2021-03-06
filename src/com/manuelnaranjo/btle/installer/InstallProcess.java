
package com.manuelnaranjo.btle.installer;

import android.content.Context;
import android.util.Log;

import com.stericson.RootTools.Command;
import com.stericson.RootTools.CommandCapture;
import com.stericson.RootTools.RootTools;
import com.stericson.RootTools.Shell;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstallProcess extends Thread {
    private InstallerListener mListener;
    private String mPath = null;
    private static final String TAG = StatusActivity.TAG;
    
    static final String WRAPPER_NAME="netd";
    static final String WRAPPER_PATH="/system/bin/netd";
    static final String MAIN_CONF="/system/etc/bluetooth/main.conf";
    static final String FRAMEWORK_PATH="/system/framework/btle-framework.jar";
    static final String PERM_PATH="/system/etc/permissions/com.manuelnaranjo.broadcom.bt.le.xml";
    static final String LAUNCH_PATH="/system/bin/btle-framework";
    
    static final String GATTTOOL="gatttool-btle";
    static final String HCITOOL="hcitool-btle";
    
    private static final String SH_HEAD="#!/system/bin/sh";

    public InstallProcess(InstallerListener l) {
        mListener = l;
        mListener.clearLog();
    }
    
    private void cleanup(){
        if (mPath!=null){
            CommandCapture cmd = new CommandCapture(0, "busybox rm -rf " + mPath + File.separator + "*");
            try {
                RootTools.getShell(true).add(cmd).waitForFinish();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        RootTools.remount("/system", "RO");
    }
    
    private boolean chmod(String path, String perm){
        try{
            RootTools.remount("/system", "RW");
            Command cmd = RootTools.getShell(true).add(
                    new CommandCapture(0, "chmod " + perm + " " + path));
            RootTools.remount("/system", "RO");
            if (cmd.exitCode()!= 0)
                throw new RuntimeException("file: " + path + 
                        ", exit code: " + cmd.exitCode());
            return true;
        } catch (Exception e){
            this.mListener.addToLog("Error while doing chmod: " + 
                        e.getMessage());
            Log.e(TAG, "error on chmod", e);
        }
        return false;
    }
    
    private boolean chown(String path, String perm){
        try{
            RootTools.remount(path, "RW");
            Command cmd = RootTools.getShell(true).add(
                    new CommandCapture(0, "chown " + perm +" " + path));
            if (cmd.exitCode()!= 0)
                throw new RuntimeException("file: " + path + 
                        ", exit code: " + cmd.exitCode());
            return true;
        } catch (Exception e){
            this.mListener.addToLog("Error while doing chown: " + 
                    e.getMessage());
            Log.e(TAG, "error on chown", e);
        }
        return false;
    }
    
    private String getFileHeader(String path, int max_length){
        String out = null;
        
        File f = new File(path);
        
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            if (max_length < 0){
                return br.readLine();
            }
            char[] t = new char[max_length];
            int l = br.read(t, 0, max_length);
            out = new String(t, 0, l);
        } catch (IOException e){
            this.mListener.addToLog("Failed to read " + path);
            Log.e(TAG, "failed to read", e);
        }
        
        return out;
    }
    
    private boolean processWrapper(Context c){
        if (!RootTools.copyFile(WRAPPER_PATH, 
                WRAPPER_PATH + ".orig", true, true)){
            mListener.addToLog("Failed to copy wrapper exe");
            return false;
        }
        mListener.addToLog("backed up wrapper");
        
        boolean ret;
        ret = RootTools.installBinary(c, R.raw.wrapper, WRAPPER_NAME, "0755");
        if (!ret){
            mListener.addToLog("failed to extract wrapper replacement");
            return false;
        }
        
        String ipath = mPath + File.separator + WRAPPER_NAME;
        mListener.addToLog("extracted wrapper");
        
        Shell s = null;
        try {
            s = RootTools.getShell(true);
        } catch (Exception e){
            mListener.addToLog("Failed to get a new rooted shell");
            return false;
        }
        mListener.addToLog("got rooted shell");
        
        CommandCapture cmd;
        cmd = new CommandCapture(0, "ls -n " + WRAPPER_PATH);
        try {
            if (s.add(cmd).exitCode()!=0){
                mListener.addToLog("Failed to get wrapper owner");
                return false;
            }
        } catch (Exception e){
            mListener.addToLog("Failed to run ls on wrapper");
            return false;
        }
        mListener.addToLog("got wrapper owner");
        
        String[] t = cmd.toString().split("[ ]+");
        String oid = t[1];
        String gid = t[2];
        
        if (!chown(ipath, oid+":"+gid)){
            mListener.addToLog("Failed to change wrapper owner");
            return false;
        }
        
        mListener.addToLog("chown of wrapper succesful");
        
        ret = RootTools.copyFile(ipath, WRAPPER_PATH, true, true);
        if (!ret){
            mListener.addToLog("Failed to overwriter original with wrapper");
            return false;
        }
                
        mListener.addToLog("Wrapper installed");
        return true;
    }
    
    private boolean installBinary(int src, String resname, String target, String perm){
        boolean ret;
        Context c;
        c=mListener.getApplicationContext();
        
        ret = RootTools.installBinary(c, src, resname, perm);
        if (!ret){
            mListener.addToLog("Failed to extract resource " + resname);
            return false;
        }
        
        mListener.addToLog("Copied resource to " + resname);
        
        String ipath;
        ipath = mPath+File.separator+resname;
        if (!chmod(ipath, perm)) {
            return false;
        }
        
        ret = RootTools.copyFile(ipath, target, true, true);
        if (!ret){
            mListener.addToLog("Failed to copy framework into " + target);
            return false;
        }
        
        return true;

    }
    
    private static final Pattern ENABLE_LE_PATTERN = Pattern.compile(
            "EnableLE\\s*=\\s*(\\S*)\\s*");

    private boolean updateMainConf(){
        boolean ret;
               
        ret = RootTools.copyFile(MAIN_CONF, mPath + "/main.conf", true, false);
        
        if (!ret){
            mListener.addToLog("Failed to copy main.conf for verification");
            return false;
        }
        
        if (!chmod(mPath+"/main.conf", "0666")){
            mListener.addToLog("Failed to set proper permissions to main.conf copy");
            return false;
        }
        
        mListener.addToLog("Copied main.conf");
        
        File f = new File(mPath + "/main.conf");
        
        int i = 0, lineToUpdate = -1;
        StringBuilder text = new StringBuilder();
        
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;

            while ((line = br.readLine()) != null) {
                text.append(line);
                text.append('\n');
                Matcher m = ENABLE_LE_PATTERN.matcher(line);
                if (m!=null && m.find()){
                    String l = m.group(1);
                    if (!l.toLowerCase().equals("true"))
                        lineToUpdate = i;
                    else
                        lineToUpdate = -2;
                }
                i++;
            }
            if (br.readLine()==null && lineToUpdate == -1){
                mListener.addToLog("Couldn't find EnableLE line, adding at the end");
                text.append("EnableLE = true\n");
                lineToUpdate = i;
            }
            br.close();
        }
        catch (IOException e) {
            Log.e(TAG, "failed to read main.conf", e);
            mListener.addToLog("Failed to read main.conf");
            return false;
        }
        
        if (lineToUpdate < 0){
            mListener.addToLog("No need to update main.conf");
            return true;
        }
        
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(f));
            i = 0;
            for (String line: text.toString().split("\n")){
                if (i!=lineToUpdate){
                    bw.write(line);
                } else {
                    bw.write("EnableLE = true");
                }
                bw.write("\n");
                i++;
            }
            bw.close();
        } catch (IOException e){
            Log.e(TAG, "creating new main.conf", e);
            mListener.addToLog("Failed while updating main.conf");
            return false;
        }
        
        ret = RootTools.copyFile(MAIN_CONF,
                MAIN_CONF + ".orig", true, true);
        
        if (!ret){
            mListener.addToLog("Failed to make main.conf backup");
            return false;
        }
        
        ret = chmod (mPath+"/main.conf", "0444");
        if (!ret){
            mListener.addToLog("Failed to set main.conf permissions");
            return false;
        }
        
        ret = chown (mPath+"/main.conf", "0:0");
        if (!ret){
            mListener.addToLog("Failed to change owner");
            return false;
        }
        
        ret = RootTools.copyFile(mPath+"/main.conf", MAIN_CONF,
                true, true);
        if (!ret){
            mListener.addToLog("Failed to update main.conf");
            return false;
        }
        
        return true;
    }
    
    private boolean setMainConfPermissions(){
        boolean ret = chmod (MAIN_CONF, "0444");
        if (!ret){
            mListener.addToLog("Failed to set main.conf permissions");
            return false;
        }
        
        ret = chown (MAIN_CONF, "0:0");
        if (!ret){
            mListener.addToLog("Failed to change owner");
            return false;
        }
        
        mListener.addToLog("Fixed main.conf permissions");
        
        return true;
    }
    
    private boolean installBinaryExecutable(Context context, String name, String oname){
    	String libraryPath = context.getApplicationInfo().dataDir + "/lib";
    	String soPath = new File(libraryPath, "lib"+name+".so").getAbsolutePath();
    	
    	if (!new File(soPath).canRead()){
    		mListener.addToLog("Can't find: " + soPath );
    		return false;
    	}
  
    	boolean ret = RootTools.copyFile(soPath, oname, true, true);
    	
    	if (!ret){
    		mListener.addToLog("Failed to copy " + soPath + " to " + oname);
    		return false;
    	}
    	
    	if (!chown(oname, "0:0")){
    		mListener.addToLog("Failed to set owner for " + oname);
    		return false;
    	}
    	
    	if (!chmod(oname, "777")){
    		mListener.addToLog("Failed to set permissions for " + oname);
    		return false;
    	}
    	
    	mListener.addToLog(name + " installed correctly");
    	return true;
    }

    public void run() {
        Context c;
        String fname;
        

        RootTools.debugMode = true;
        
        List<String> applets;
        try {
            applets = RootTools.getBusyBoxApplets("/system/xbin");
            for (String s: applets)
                Log.v(TAG, "provides applet " + s);
        } catch (Exception e1) {
            Log.e(TAG, "error", e1);
        }
        
        
        c = mListener.getApplicationContext();
        try {
            mPath = c.getFilesDir().getCanonicalPath();
        } catch (IOException e) {
           Log.e(TAG, "failed to get canonical path", e);
           mListener.addToLog("failed to get canonical path");
           return;
        }
        
        fname = "btle-framework-" + c.getString(R.string.current_framework_version) + ".jar";
        
        if (!this.installBinary(R.raw.btle_framework, fname, FRAMEWORK_PATH, "0644")){
            cleanup();
            return;
        }
        mListener.addToLog("Installed framework");
        
        if (!this.installBinaryExecutable(c, GATTTOOL, "/system/bin/" + GATTTOOL)){
        	cleanup();
        	return;
        }
        
        if (!this.installBinaryExecutable(c, HCITOOL, "/system/bin/" + HCITOOL)){
        	cleanup();
        	return;
        }
        
        if (!RootTools.exists(MAIN_CONF)){
            if (!this.installBinary(R.raw.main_conf, "main.conf", MAIN_CONF, "0444")){
                cleanup();
                return;
            }
        }
        
        if (!this.updateMainConf()){
            cleanup();
            return;
        }
        
        if (!this.setMainConfPermissions()){
            cleanup();
            return;
        }
        
        mListener.addToLog("Updated main.conf");
        
        fname = "com.manuelnaranjo.android.bluetooth.le.xml";
        if (!this.installBinary(R.raw.android_bluetooth_le, fname, PERM_PATH, "0644")){
            cleanup();
            return;
        }
        mListener.addToLog("Installed permission");
        
        fname = "btle-framework";
        if (!this.installBinary(R.raw.btle_framework_script, fname, LAUNCH_PATH, "0755")){
            cleanup();
            return;
        }
        mListener.addToLog("Installed btle-framework launcher");
        
        String fheader = getFileHeader(WRAPPER_PATH, SH_HEAD.length());
        Log.v(TAG, "got file header " + fheader);
        if (!SH_HEAD.equals(fheader)){
            if (!processWrapper(c)){
                cleanup();
            }
        }
        
        CommandCapture cmd = new CommandCapture(0, "/system/bin/btle-framework --version");
        try {
            RootTools.getShell(true).add(cmd).waitForFinish();
            if (cmd.exitCode()!=0){
                mListener.addToLog("WARN: failed to update dalvik cache");
                cleanup();
                return;
            }
        } catch (Exception e) {
            mListener.addToLog("WARN: failed to update dalvik cache");
            Log.e(TAG, "failed to update dalvik cache", e);
            cleanup();
            return;
        } 
        
        mListener.addToLog("Installation done");
        mListener.addToLog("It's better if you restart your cellphone");
        mListener.updateValues();
        mListener.reboot();
        cleanup();
    }
}
