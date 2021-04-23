package com.pd.xldr.xml;

import org.junit.jupiter.api.Test;

public class Simple1Test {

    @Test
    public void test() {

        // load file simple1.xml
//        var simple1 = new File(getClass().getResource("/simple1.xml").toURI());
//        var xmlfh = new XmlFileHandlerFactory().newHandler(simple1);

        // setup up input spec
//        var rowSelector = new XmlAbstractRecordSelector("row", "/simple1/row");
//        rowSelector.put(new XmlFieldSelector("cola", "col[@name='a']"));
//        rowSelector.put(new XmlFieldSelector("colb", "col[@name='b']"));
//        rowSelector.put(new XmlFieldSelector("firstcow", "//cow[1]/sound"));
//        rowSelector.put(new XmlFieldSelector("lastcow", "/simple1/cow[last()]/sound"));

        // map input
//        var recs = rowSelector
//                .records(xmlfh)
//                .map(node -> Map.of(
//                        "cola", rowSelector.get("cola").field(node),
//                        "colb", rowSelector.get("colb").field(node),
//                        "firstcow", rowSelector.get("firstcow").field(node),
//                        "lastcow", rowSelector.get("lastcow").field(node)
//                ))
//                .collect(Collectors.toList());

        // output
//        System.out.println(recs);
    }

}
