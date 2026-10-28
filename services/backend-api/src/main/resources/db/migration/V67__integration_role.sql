-- Papel para serviços que falam com o Kixi (o motor SchoolOps): vale só o que IntegrationScope lista.
INSERT INTO roles (name, description) VALUES
    ('INTEGRATION', 'Service account: reads simulations and statements, writes grades; nothing else')
ON CONFLICT (name) DO NOTHING;
