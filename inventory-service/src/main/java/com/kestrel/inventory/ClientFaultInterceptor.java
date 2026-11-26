package com.kestrel.inventory;

import static com.kestrel.inventory.soap.InventoryFaultCode.INVALID_QUANTITY;
import static com.kestrel.inventory.soap.InventoryFaultCode.INVALID_REQUEST;

import jakarta.xml.bind.UnmarshalException;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.phase.Phase;
import org.xml.sax.SAXParseException;

final class ClientFaultInterceptor extends AbstractSoapInterceptor {
    private static final Pattern QUANTITY_FIELD = Pattern.compile(
            "(?i)(?<![a-z])(?:requestedquantity|positivequantity|quantity)(?![a-z])");

    ClientFaultInterceptor() {
        super(Phase.PREPARE_SEND);
    }

    @Override
    public void handleMessage(SoapMessage message) {
        Exception error = message.getContent(Exception.class);
        if (error instanceof Fault fault && fault.hasDetails()) {
            return;
        }
        boolean invalidXml = false;
        boolean quantityField = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnmarshalException || cause instanceof XMLStreamException || cause instanceof SAXParseException) {
                invalidXml = true;
                String description = cause.getMessage();
                quantityField |= description != null && QUANTITY_FIELD.matcher(description).find();
            }
        }
        if (invalidXml) {
            var request = message.getExchange().getInMessage();
            XMLStreamReader reader = request.getContent(XMLStreamReader.class);
            if (reader != null && (reader.isStartElement() || reader.isEndElement())
                    && SoapFaults.NAMESPACE.equals(reader.getNamespaceURI())) {
                quantityField |= "quantity".equals(reader.getLocalName())
                        || "requestedQuantity".equals(reader.getLocalName());
            }
            message.setContent(Exception.class, quantityField
                    ? SoapFaults.sender(INVALID_QUANTITY, "Quantity must be a positive integer.")
                    : SoapFaults.sender(INVALID_REQUEST, "The request does not match the Inventory schema."));
        }
    }
}
