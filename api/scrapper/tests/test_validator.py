import pytest

from app.scraper.validator import domain_has_public_dns, is_public_ip


@pytest.mark.asyncio
async def test_domain_has_public_dns_rejects_loopback_literal() -> None:
    assert await domain_has_public_dns("127.0.0.1") is False


def test_private_ips_are_not_public() -> None:
    import ipaddress

    assert is_public_ip(ipaddress.ip_address("10.0.0.1")) is False
    assert is_public_ip(ipaddress.ip_address("192.168.1.10")) is False
