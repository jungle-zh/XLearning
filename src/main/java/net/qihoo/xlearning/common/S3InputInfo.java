package net.qihoo.xlearning.common;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by admin on 18/5/2.
 */
public class S3InputInfo implements Writable {


    public S3InputInfo() {

    }

    public String getAliasName() {
        return aliasName;
    }


    public List<String> getPaths() {
        return paths;
    }

    public String getBucket() {
        return bucket;
    }
    public void setAliasName(String aliasName) {
        this.aliasName = aliasName;
    }
    public void setBucket(String bucket){
        this.bucket = bucket;
    }
    private List<String> paths  = new ArrayList<>();
    private String aliasName;
    private String bucket ;



    @Override
    public void write(DataOutput dataOutput) throws IOException {
        System.out.print("write ### aliasName :" + aliasName + " bucket name :" + bucket);
        Text.writeString(dataOutput, aliasName);
        Text.writeString(dataOutput, bucket);
        dataOutput.writeInt(paths.size());
        for (String p : paths) {
            Text.writeString(dataOutput, p.toString());
        }

    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {

        this.aliasName = Text.readString(dataInput);
        this.bucket = Text.readString(dataInput);
        int size = dataInput.readInt();
        this.paths = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            this.paths.add(Text.readString(dataInput));
        }
        System.out.print("read ### aliasName :" + aliasName + " bucket name :" + bucket);

    }
}
