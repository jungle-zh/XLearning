package net.qihoo.xlearning.common;

/**
 * Created by admin on 18/5/3.
 */
public class S3ObjectEntry {


    public S3ObjectEntry(String bucket,String path, String aliaseDir){
        this.bucket  = bucket;
        this.path = path;
        this.aliaseDir = aliaseDir;
    }

    private String bucket ;
    private String path ;
    private String aliaseDir;

    public String getBucket() {
        return bucket;
    }

    public String getPath() {
        return path;
    }

    public String getAliaseDir() {
        return aliaseDir;
    }
     public String toString(){

        String res = "";
        res += bucket;
        res += ":";
        res += path;
        res += ":";
        res += aliaseDir;
        return res;

    }
}
