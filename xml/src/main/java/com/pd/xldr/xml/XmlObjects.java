package com.pd.xldr.xml;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

interface XmlObjects {
    XPath XP = XPathFactory.newDefaultInstance().newXPath();
    DocumentBuilderFactory PARSER = DocumentBuilderFactory.newDefaultInstance();
}
