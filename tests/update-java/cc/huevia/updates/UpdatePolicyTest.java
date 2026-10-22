package cc.huevia.updates;

/** Pure-Java policy boundaries only; APK verification and DownloadManager require Android runtime. */
public final class UpdatePolicyTest {
    private static int checks;
    public static void main(String[] args) {
        String base="https://updates.example.test/preset-library",id="19bd1434-e597-4f27-808d-9b86eb8d983d",url=base+"/releases/"+id+"/file";
        require(UpdatePolicy.trustedUrl(base,url,id),"canonical product download allowed");
        for(String bad:new String[]{"http://updates.example.test/preset-library/releases/"+id+"/file","https://updates.example.test.evil.test/preset-library/releases/"+id+"/file","https://evil.test/preset-library/releases/"+id+"/file",url+"?token=secret",url+"#fragment",url.replace("https://","https://user:pass@"),url.replace("example.test/","example.test:443/"),url.replace("/releases/","/../releases/"),url.replace("/releases/","/%72eleases/"),url.replace("/file","/file/"),url.replace("/file","/file%2f..%2fphotos"),null,"",url+"\nInjected: value"})require(!UpdatePolicy.trustedUrl(base,bad,id),"untrusted destination rejected");
        for(String hash:new String[]{"a".repeat(64),"0".repeat(64),"0123456789abcdef".repeat(4)})require(UpdatePolicy.hash(hash),"valid content hash");
        for(String bad:new String[]{null,"","a".repeat(63),"a".repeat(65),"A".repeat(64),"g".repeat(64),"../"+"a".repeat(61),"a".repeat(63)+"\n"})require(!UpdatePolicy.hash(bad),"hash cannot construct other file paths");
        require(UpdatePolicy.newer(53,54),"strict upgrade");require(!UpdatePolicy.newer(54,54),"same version not reinstalled");require(!UpdatePolicy.newer(54,53),"downgrade rejected");require(!UpdatePolicy.newer(0,54),"unknown installed version fails closed");require(!UpdatePolicy.newer(54,0),"unknown candidate version rejected");
        require(UpdatePolicy.otherPackage("cc.huevia","com.nikonpreset.usbprobe"),"a pre-migration server's old-package response cannot provide an update");
        require(!UpdatePolicy.otherPackage("cc.huevia","cc.huevia"),"same-package releases still receive normal validation");
        require(UpdatePolicy.otherPackage("cc.huevia","another.app"),"other applications cannot provide an update");
        require(UpdatePolicy.otherPackage("com.nikonpreset.usbprobe","cc.huevia"),"a new install ID cannot update a different installed application");
        require(!UpdatePolicy.otherPackage(null,null),"missing package identities still fail normal validation");
        require(!UpdatePolicy.otherPackage("cc.huevia",""),"an empty identity is not treated as a valid other application");
        require(UpdatePolicy.compatible(35,26)&&UpdatePolicy.compatible(35,35),"supported Android SDK accepted");require(!UpdatePolicy.compatible(25,26)&&!UpdatePolicy.compatible(35,36)&&!UpdatePolicy.compatible(35,0),"unsupported SDK rejected");
        require(UpdatePolicy.percent(0,100)==0&&UpdatePolicy.percent(-1,100)==0&&UpdatePolicy.percent(1,0)==0,"unknown and negative progress bounded");require(UpdatePolicy.percent(51,100)==51&&UpdatePolicy.percent(1000,100)==100,"normal progress bounded at 100");require(UpdatePolicy.percent(Long.MAX_VALUE,Long.MAX_VALUE)==100,"large byte counts do not overflow");
        System.out.println("Update policy: "+checks+" checks passed (host-only).");
    }
    private static void require(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
}
