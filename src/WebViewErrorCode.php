<?php

declare(strict_types=1);

namespace Pam\Native\WebView;

/** Why a web view reported `onError`; the second argument of the handler. */
enum WebViewErrorCode: string
{
    /** The main-frame load failed (no connection, DNS, TLS, HTTP stack error). */
    case Load = 'load';

    /** The first page did not finish within 30 seconds. */
    case Timeout = 'timeout';

    /**
     * The page being loaded (or a redirect while it loads) is outside
     * `allowedHosts`. A blocked link or redirect on a page that already
     * finished is only cancelled and reports nothing.
     */
    case Blocked = 'blocked';

    /**
     * The web content process died: the Android renderer crashed or was
     * killed (the app keeps running and this view is retired), or iOS
     * terminated the WebContent process (the page is blank). Remount the web
     * view (new key) to load again, or show a failure.
     */
    case ProcessGone = 'process-gone';

    /** Older native sides send no code: a load failure. */
    public static function fromWire(mixed $value): self
    {
        return is_string($value) ? (self::tryFrom($value) ?? self::Load) : self::Load;
    }
}
