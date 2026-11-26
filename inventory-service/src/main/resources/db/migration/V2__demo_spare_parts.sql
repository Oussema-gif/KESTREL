INSERT INTO inventory.spare_parts (id, sku, name, description, unit, available_quantity, reorder_level)
VALUES
    ('00000000-0000-4000-8000-000000000101', 'BRG-6205', 'Motor bearing',
     'Sealed bearing for the demo drive motor', 'EA', 12, 3),
    ('00000000-0000-4000-8000-000000000102', 'FLT-HYD-10', 'Hydraulic filter',
     'Replacement filter cartridge', 'EA', 2, 2),
    ('00000000-0000-4000-8000-000000000103', 'SNS-TEMP-01', 'Temperature sensor',
     'Replacement sensor for the demo machine', 'EA', 0, 2);
