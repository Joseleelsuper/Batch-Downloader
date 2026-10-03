-- Conserva qué finalización del Scraper ya está incorporada, incluso tras reiniciar.
ALTER TABLE semantic_index_state ADD COLUMN last_scrape_run_id UUID;
