package com.kestrel.inventory;

import static javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI;

import com.kestrel.inventory.soap.InventoryFault;
import com.kestrel.inventory.soap.InventoryFaultCode;
import com.kestrel.inventory.soap.InventoryFault_Exception;
import org.apache.cxf.binding.soap.Soap11;
import org.apache.cxf.binding.soap.SoapFault;
import org.w3c.dom.Element;

final class SoapFaults {
    static final String NAMESPACE = "urn:kestrel:inventory:v1";

    private SoapFaults() {
    }

    static InventoryFault_Exception declared(InventoryProblem problem) {
        var detail = new InventoryFault();
        detail.setCode(problem.code);
        detail.setMessage(problem.getMessage());
        detail.setAvailableQuantity(problem.availableQuantity);
        return new InventoryFault_Exception(problem.getMessage(), detail);
    }

    static SoapFault sender(InventoryFaultCode code, String text) {
        var fault = new SoapFault(text, Soap11.getInstance().getSender());
        Element wrapper = fault.getOrCreateDetail();
        Element detail = wrapper.getOwnerDocument().createElementNS(NAMESPACE, "inv:inventoryFault");
        detail.setAttributeNS(XMLNS_ATTRIBUTE_NS_URI, "xmlns:inv", NAMESPACE);
        wrapper.appendChild(detail);
        append(detail, "code", code.value());
        append(detail, "message", text);
        return fault;
    }

    private static void append(Element parent, String name, String value) {
        Element child = parent.getOwnerDocument().createElementNS(NAMESPACE, "inv:" + name);
        child.setTextContent(value);
        parent.appendChild(child);
    }
}
