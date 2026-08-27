import os
import ssl


if os.environ.get("AI_FACTORY_LEGACY_CA_COMPATIBILITY") == "true":
    create_default_context = ssl.create_default_context

    def create_legacy_ca_context(*args, **kwargs):
        context = create_default_context(*args, **kwargs)
        context.verify_flags &= ~ssl.VERIFY_X509_STRICT
        return context

    ssl.create_default_context = create_legacy_ca_context