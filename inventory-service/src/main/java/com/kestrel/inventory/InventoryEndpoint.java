package com.kestrel.inventory;

import static com.kestrel.inventory.SoapFaults.NAMESPACE;
import static com.kestrel.inventory.soap.InventoryFaultCode.*;

import com.kestrel.inventory.soap.CheckStockRequest;
import com.kestrel.inventory.soap.CheckStockResponse;
import com.kestrel.inventory.soap.CreatePurchaseOrderRequest;
import com.kestrel.inventory.soap.CreatePurchaseOrderResponse;
import com.kestrel.inventory.soap.GetSparePartRequest;
import com.kestrel.inventory.soap.GetSparePartResponse;
import com.kestrel.inventory.soap.InventoryFault_Exception;
import com.kestrel.inventory.soap.InventoryPortType;
import com.kestrel.inventory.soap.PurchaseOrderStatus;
import com.kestrel.inventory.soap.ReleaseReservationRequest;
import com.kestrel.inventory.soap.ReleaseReservationResponse;
import com.kestrel.inventory.soap.ReservePartRequest;
import com.kestrel.inventory.soap.ReservePartResponse;
import jakarta.jws.WebService;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.xml.datatype.DatatypeFactory;
import org.apache.cxf.annotations.SchemaValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

@Component
@WebService(endpointInterface = "com.kestrel.inventory.soap.InventoryPortType", targetNamespace = NAMESPACE,
        serviceName = "InventoryService", portName = "InventorySoapPort")
@SchemaValidation(type = SchemaValidation.SchemaValidationType.BOTH)
public class InventoryEndpoint implements InventoryPortType {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryEndpoint.class);
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final InventoryStock stock;

    InventoryEndpoint(InventoryStock stock) {
        this.stock = stock;
    }

    @Override
    public GetSparePartResponse getSparePart(GetSparePartRequest request) throws InventoryFault_Exception {
        return perform(() -> {
            var part = stock.getPart(uuid(request.getPartId()));
            var response = new GetSparePartResponse();
            response.setPartId(part.id().toString());
            response.setSku(part.sku());
            response.setName(part.name());
            response.setDescription(part.description());
            response.setUnit(part.unit());
            response.setAvailableQuantity(part.available());
            response.setReorderLevel(part.reorderLevel());
            return response;
        });
    }

    @Override
    public CheckStockResponse checkStock(CheckStockRequest request) throws InventoryFault_Exception {
        return perform(() -> {
            int quantity = quantity(request.getRequestedQuantity());
            var part = stock.getPart(uuid(request.getPartId()));
            var response = new CheckStockResponse();
            response.setPartId(part.id().toString());
            response.setRequestedQuantity(quantity);
            response.setAvailableQuantity(part.available());
            response.setAvailable(part.available() >= quantity);
            return response;
        });
    }

    @Override
    public ReservePartResponse reservePart(ReservePartRequest request) throws InventoryFault_Exception {
        return perform(() -> {
            var result = stock.reserve(uuid(request.getPartId()), quantity(request.getQuantity()),
                    uuid(request.getWorkOrderId()), uuid(request.getRequestId()));
            var response = new ReservePartResponse();
            response.setReservationId(result.id().toString());
            response.setReservedQuantity(result.quantity());
            response.setStatus(result.status());
            return response;
        });
    }

    @Override
    public ReleaseReservationResponse releaseReservation(ReleaseReservationRequest request) throws InventoryFault_Exception {
        return perform(() -> {
            var result = stock.release(uuid(request.getReservationId()), text(request.getReason()));
            var response = new ReleaseReservationResponse();
            response.setReservationId(result.id().toString());
            response.setStatus(result.status());
            response.setReleasedAt(DatatypeFactory.newDefaultInstance()
                    .newXMLGregorianCalendar(result.releasedAt().toInstant().toString()));
            return response;
        });
    }

    @Override
    public CreatePurchaseOrderResponse createPurchaseOrder(CreatePurchaseOrderRequest request) throws InventoryFault_Exception {
        return perform(() -> {
            UUID id = stock.purchaseOrder(uuid(request.getPartId()), quantity(request.getQuantity()),
                    text(request.getSupplierReference()));
            var response = new CreatePurchaseOrderResponse();
            response.setPurchaseOrderId(id.toString());
            response.setStatus(PurchaseOrderStatus.CREATED);
            return response;
        });
    }

    private static <T> T perform(Supplier<T> operation) throws InventoryFault_Exception {
        try {
            return operation.get();
        } catch (InventoryProblem problem) {
            throw SoapFaults.declared(problem);
        } catch (TransientDataAccessException | CannotGetJdbcConnectionException | CannotCreateTransactionException unavailable) {
            throw SoapFaults.declared(new InventoryProblem(SERVICE_UNAVAILABLE, "Inventory storage is temporarily unavailable."));
        } catch (RuntimeException error) {
            LOG.error("Unexpected Inventory SOAP failure ({})", error.getClass().getSimpleName());
            throw SoapFaults.declared(new InventoryProblem(INTERNAL_ERROR, "An unexpected inventory error occurred."));
        }
    }

    private static UUID uuid(String value) {
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            throw new InventoryProblem(INVALID_REQUEST, "An identifier is not a canonical UUID.");
        }
        return UUID.fromString(value);
    }

    private static int quantity(int value) {
        if (value < 1) {
            throw new InventoryProblem(INVALID_QUANTITY, "Quantity must be a positive integer.");
        }
        return value;
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new InventoryProblem(INVALID_REQUEST, "A required text field is empty or too long.");
        }
        return value;
    }
}
