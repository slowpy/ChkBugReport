package com.sonyericsson.chkbugreport.plugins;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Vector;

import com.sonyericsson.chkbugreport.Chapter;
import com.sonyericsson.chkbugreport.Lines;
import com.sonyericsson.chkbugreport.Plugin;
import com.sonyericsson.chkbugreport.Module;
import com.sonyericsson.chkbugreport.Section;
import com.sonyericsson.chkbugreport.SectionInputStream;
import com.sonyericsson.chkbugreport.util.TableGen;
import com.sonyericsson.chkbugreport.util.XMLNode;

public class PackageInfoPlugin extends Plugin {

    private static final String TAG = "[PackageInfoPlugin]";

    private Chapter mCh;
    private Chapter mChPackages;
    private Chapter mChUids;
    private Chapter mChPermissions;
    private XMLNode mPackagesXml;
    private HashMap<Integer, UID> mUIDs = new HashMap<Integer, PackageInfoPlugin.UID>();
    private HashMap<String, PackageInfo> mPackages = new HashMap<String, PackageInfoPlugin.PackageInfo>();
    private HashMap<String, Vector<UID>> mPermissions = new HashMap<String, Vector<UID>>();

    @SuppressWarnings("serial")
    public class Permissions extends Vector<String> {
    }

    public class PackageInfo {
        private int mId;
        private String mName;
        private String mPath;
        private String mOrigPath;
        private int mFlags;
        private UID mUID;
        private Permissions mPermissions = new Permissions();

        public PackageInfo(int id, String pkg, String path, int flags, UID uidObj) {
            mId = id;
            mName = pkg;
            mPath = path;
            mOrigPath = null;
            mFlags = flags;
            mUID = uidObj;
            uidObj.add(this);
        }

        public Permissions getPermissions() {
            return mPermissions;
        }

        public int getId() {
            return mId;
        }

        public String getName() {
            return mName;
        }

        public String getPath() {
            return mPath;
        }

        public String getOrigPath() {
            return mOrigPath;
        }

        void setOrigPath(String origPath) {
            mOrigPath = origPath;
        }

        public int getFlags() {
            return mFlags;
        }

        public UID getUid() {
            return mUID;
        }

        public void dumpInfo(Lines out) {
            out.addLine("<pre class=\"box\">");
            out.addLine("Name:        " + mName);
            out.addLine("Path:        " + mPath);
            out.addLine("OrigPath:    " + mOrigPath);
            out.addLine("Flags:       " + "0x" + Integer.toHexString(mFlags));
            out.addLine("Permissions: ");
            for (String perm : mPermissions) {
                out.addLine("             " + perm);
            }
            out.addLine("</pre>");
        }

    }

    public class UID {
        private int mUid;
        private Vector<PackageInfo> mPackages = new Vector<PackageInfoPlugin.PackageInfo>();
        private Permissions mPermissions = new Permissions();
        private String mName;
        private Chapter mChapter;

        public UID(int uid) {
            mUid = uid;
        }

        public Permissions getPermissions() {
            return mPermissions;
        }

        public void add(PackageInfo pkg) {
            mPackages.add(pkg);
            if (mName == null) {
                mName = pkg.getName();
            }
        }

        public String getName() {
            return mName;
        }

        public int getUid() {
            return mUid;
        }

        public int getPackageCount() {
            return mPackages.size();
        }

        public PackageInfo getPackage(int idx) {
            return mPackages.get(idx);
        }

        public String getFullName() {
            if (mName == null) {
                return Integer.toString(mUid);
            } else {
                return mName + "(" + Integer.toString(mUid) + ")";
            }
        }

        void setName(String name) {
            mName = name;
        }

        void setChapter(Chapter cch) {
            mChapter = cch;
        }

        Chapter getChapter() {
            return mChapter;
        }
    }

    @Override
    public int getPrio() {
        return 1; // Load data ASAP
    }

    @Override
    public void load(Module br) {
        // reset
        mCh = null;
        mChPackages = null;
        mChUids = null;
        mChPermissions = null;
        mPackagesXml = null;
        mUIDs.clear();
        mPackages.clear();
        mPermissions.clear();

        // Load packages.xml
        Section s = br.findSection(Section.PACKAGE_SETTINGS);
        if (s == null) {
            br.printErr(3, TAG + "Cannot find section: " + Section.PACKAGE_SETTINGS);
            return;
        }

        if (s.getLineCount() == 0 || s.getLine(0).startsWith("***")) {
            br.printErr(4, TAG + "Cannot parse section: " + Section.PACKAGE_SETTINGS);
            return;
        }

        SectionInputStream is = new SectionInputStream(s);
        mPackagesXml = XMLNode.parse(is);
        mCh = new Chapter(br, "Package info");
        br.addChapter(mCh);
        mChPackages = new Chapter(br, "Packages");
        mCh.addChapter(mChPackages);
        mChUids = new Chapter(br, "UserIDs");
        mCh.addChapter(mChUids);
        mChPermissions = new Chapter(br, "Permissions");
        mCh.addChapter(mChPermissions);

        // Create the UID for the kernel/root manually
        getUID(0, true).setName("kernel/root");

        // Parse XML for shared-user tags
        for (XMLNode child : mPackagesXml.getChildren("shared-user")) {
            String name = child.getAttr("name");
            String sUid = child.getAttr("userId");
            int uid = Integer.parseInt(sUid);

            UID uidObj = getUID(uid, true);
            uidObj.setName(name);
            collectPermissions(uidObj.getPermissions(), uidObj, child);
        }

        // Parse XML for packages
        int pkgId = 0;
        for (XMLNode child : mPackagesXml.getChildren("package")) {
            String pkg = child.getAttr("name");
            String path = child.getAttr("codePath");
            String sUid = child.getAttr("userId");
            if (sUid == null) {
                sUid = child.getAttr("sharedUserId");
            }
            int uid = Integer.parseInt(sUid);
            String sFlags = child.getAttr("flags");
            int flags = (sFlags == null) ? 0 : Integer.parseInt(sFlags);

            UID uidObj = getUID(uid, true);
            PackageInfo pkgObj = new PackageInfo(++pkgId, pkg, path, flags, uidObj);
            mPackages.put(pkg, pkgObj);

            collectPermissions(pkgObj.getPermissions(), uidObj, child);
        }

        // Parse XML for updated-package tags
        for (XMLNode child : mPackagesXml.getChildren("updated-package")) {
            String pkg = child.getAttr("name");
            String path = child.getAttr("codePath");

            PackageInfo pkgObj = mPackages.get(pkg);
            if (pkgObj != null) {
                pkgObj.setOrigPath(path);
                collectPermissions(pkgObj.getPermissions(), pkgObj.getUid(), child);
            } else {
                System.err.println("Could not find package for updated-package item: " + pkg);
            }
        }

        // Create a chapter to each UID, so we can link to it from other plugins
        for (UID uid : mUIDs.values()) {
            Chapter cch = new Chapter(br, uid.getFullName());
            uid.setChapter(cch);
            mChUids.addChapter(cch);
        }
    }

    private void collectPermissions(Permissions perm, UID uid, XMLNode pkgNode) {
        XMLNode node = pkgNode.getChild("perms");
        if (node != null) {
            for (XMLNode item : node.getChildren("item")) {
                String permission = item.getAttr("name");
                perm.add(permission);

                // Store each UID who has this permission
                Vector<UID> uids = mPermissions.get(permission);
                if (uids == null) {
                    uids = new Vector<PackageInfoPlugin.UID>();
                    mPermissions.put(permission, uids);
                }
                if (!uids.contains(uid)) {
                    uids.add(uid);
                }
            }
            Collections.sort(perm);
        }
    }

    @Override
    public void generate(Module br) {
        if (mChPackages != null) {
            generatePackageList(br, mChPackages);
        }
        if (mChPermissions != null) {
            generatePermissionList(br, mChPermissions);
        }
    }

    private void generatePermissionList(Module br, Chapter ch) {
        Vector<String> tmp = new Vector<String>();
        for (String s : mPermissions.keySet()) {
            tmp.add(s);
        }
        Collections.sort(tmp);

        for (String s : tmp) {
            ch.addLine("<h2>" + s + "</h2>");
            ch.addLine("<ul>");
            Vector<UID> uids = mPermissions.get(s);
            for (UID uid : uids) {
                int cnt = uid.getPackageCount();
                if (cnt == 0) {
                    ch.addLine("<li>" + uid.getFullName() + "</li>");
                } else {
                    for (int i = 0; i < cnt; i++) {
                        PackageInfo pkg = uid.getPackage(i);
                        ch.addLine("<li>" + uid.getFullName() + ": " + pkg.getName() + "</li>");
                    }
                }
            }
            ch.addLine("</ul>");
        }

    }

    private void generatePackageList(Module br, Chapter ch) {

        // Create a chapter for each user id
        Vector<UID> tmp = new Vector<PackageInfoPlugin.UID>();
        for (UID uid : mUIDs.values()) {
            tmp.add(uid);
        }
        Collections.sort(tmp, new Comparator<UID>() {
            @Override
            public int compare(UID o1, UID o2) {
                return o1.mUid - o2.mUid;
            }
        });
        for (UID uid : tmp) {
            Chapter cch = uid.getChapter();

            cch.addLine("<p>Packages:</p>");
            int cnt = uid.getPackageCount();
            for (int i = 0; i < cnt; i++) {
                PackageInfo pkg = uid.getPackage(i);
                pkg.dumpInfo(cch);
            }

            cch.addLine("<p>Permissions:</p>");
            cch.addLine("<pre class=\"box\">");
            for (String perm : uid.getPermissions()) {
                cch.addLine("             " + perm);
            }
            cch.addLine("</pre>");
        }

        // Create a ToC for the packages
        ch.addLine("<p>Installed packages:</p>");

        TableGen tg = new TableGen(ch, TableGen.FLAG_SORT);
        tg.setCSVOutput(br, "package_list");
        tg.setTableName(br, "package_list");
        tg.addColumn("Package", null, "pkg varchar", TableGen.FLAG_NONE);
        tg.addColumn("Path", null, "path varchar", TableGen.FLAG_NONE);
        tg.addColumn("UID", null, "uid int", TableGen.FLAG_ALIGN_RIGHT);
        tg.addColumn("Flags", null, "flags int", TableGen.FLAG_ALIGN_RIGHT);
        tg.begin();

        for (PackageInfo pkg : mPackages.values()) {
            String link = getLinkToUid(br, pkg.getUid());
            tg.addData(link, pkg.getName(), TableGen.FLAG_NONE);
            tg.addData(pkg.getPath());
            tg.addData(Integer.toString(pkg.getUid().getUid()));
            tg.addData(Integer.toHexString(pkg.getFlags()));
        }

        tg.end();
    }

    public String getLinkToUid(Module br, UID uid) {
        if (uid == null) {
            return null;
        }
        return br.getRefToChapter(uid.getChapter());
    }

    public UID getUID(int uid) {
        return getUID(uid, false);
    }

    private UID getUID(int uid, boolean createIfNeeded) {
        UID ret = mUIDs.get(uid);
        if (ret == null && createIfNeeded) {
            ret = new UID(uid);
            mUIDs.put(uid, ret);
        }
        return ret;
    }

    public boolean isEmpty() {
        return mPackages.isEmpty();
    }

    public Collection<PackageInfo> getPackages() {
        return mPackages.values();
    }

}