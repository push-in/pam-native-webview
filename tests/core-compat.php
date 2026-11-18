<?php
declare(strict_types=1);
use Pam\Native\EventKind;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\Wire;
use Pam\Native\NodeKind;
use Pam\Native\ScrollOverScrollMode;
use Pam\Native\PropKey;
use Pam\Native\WebView\WebView;


$lastCall = static function (): array {
    $call = TestDiagnostics::$moduleCall;
    if ($call === null) {
        throw new RuntimeException('No native module call was recorded.');
    }

    return $call + ['values' => $call['payload'] === '' ? [] : Wire::decodeMap($call['payload'])];
};
$rejects = static function (Closure $operation): bool {
    try {
        $operation();
    } catch (InvalidArgumentException|RuntimeException|LogicException) {
        return true;
    }

    return false;
};


$hardenedWebView = WebView::make('https://example.com/app')
    ->allowedHosts(['example.com', 'cdn.example.com']);
$assert(
    $hardenedWebView->properties()[PropKey::WebViewAllowedHosts->value]
        === "example.com\ncdn.example.com"
        && PropKey::WebViewAllowedHosts->value === 366,
    'WebView host allowlists must use the append-only native protocol.',
);

// A fixed full-screen page (a game): no scroll, bounce or over-scroll, on the
// existing scroll keys (no new protocol numbers).
$fixedWebView = WebView::make('https://example.com/app')
    ->scrollEnabled(false)
    ->overScrollMode(ScrollOverScrollMode::Never);
$assert(
    $fixedWebView->properties()[PropKey::ScrollEnabled->value] === false
        && $fixedWebView->properties()[PropKey::ScrollOverScrollMode->value] === ScrollOverScrollMode::Never->value,
    'WebView must accept scrollEnabled and overScrollMode on the shared scroll keys.',
);
$fixedTemplate = TemplateRenderer::render(TemplateCompiler::compile(
    '<WebView source="https://example.com/app" scrollEnabled="false" overScrollMode="never" />',
), null, []);
$assert(
    $fixedTemplate->kind() === NodeKind::WebView
        && ($fixedTemplate->properties()[PropKey::ScrollEnabled->value] ?? null) === false
        && ($fixedTemplate->properties()[PropKey::ScrollOverScrollMode->value] ?? null) === ScrollOverScrollMode::Never->value,
    'WebView templates must forward scrollEnabled and overScrollMode.',
);
$webMessages = [];
$messageWebView = WebView::make('https://example.com/app')->onMessage(
    static function (string $message, string $url) use (&$webMessages): void {
        $webMessages[] = [$message, $url];
    },
);
$messageWebView->events()[EventKind::WebViewMessage->value](Wire::map([
    'message' => '{"type":1}',
    'url' => 'https://example.com/app',
]));
$messageWebView->events()[EventKind::WebViewMessage->value](Wire::map(['message' => 'legacy']));
$legacyMessages = [];
WebView::make('https://example.com/app')->onMessage(
    static function (string $message) use (&$legacyMessages): void {
        $legacyMessages[] = $message;
    },
)->events()[EventKind::WebViewMessage->value](Wire::map(['message' => 'one', 'url' => 'https://example.com/']));
$assert(
    $webMessages === [['{"type":1}', 'https://example.com/app'], ['legacy', '']]
        && $legacyMessages === ['one'],
    'WebView messages must carry the posting page URL and keep one-argument handlers working.',
);


// Errors carry a code; one-argument handlers keep working and an older
// native side (no code) reads as a load failure.
$webErrors = [];
$errorWebView = WebView::make('https://example.com/app')->onError(
    static function (string $message, \Pam\Native\WebView\WebViewErrorCode $code) use (&$webErrors): void {
        $webErrors[] = [$message, $code];
    },
);
$errorWebView->events()[EventKind::WebViewError->value](Wire::map([
    'message' => 'WebView renderer crashed',
    'code' => 'process-gone',
]));
$errorWebView->events()[EventKind::WebViewError->value](Wire::map(['message' => 'offline']));
$errorWebView->events()[EventKind::WebViewError->value](Wire::map(['message' => 'x', 'code' => 'future-code']));
$legacyErrors = [];
WebView::make('https://example.com/app')->onError(
    static function (string $message) use (&$legacyErrors): void {
        $legacyErrors[] = $message;
    },
)->events()[EventKind::WebViewError->value](Wire::map(['message' => 'timed out', 'code' => 'timeout']));
$assert(
    $webErrors === [
        ['WebView renderer crashed', \Pam\Native\WebView\WebViewErrorCode::ProcessGone],
        ['offline', \Pam\Native\WebView\WebViewErrorCode::Load],
        ['x', \Pam\Native\WebView\WebViewErrorCode::Load],
    ] && $legacyErrors === ['timed out'],
    'WebView errors must carry a WebViewErrorCode and keep one-argument handlers working.',
);
$assert(
    array_map(static fn ($case) => $case->value, \Pam\Native\WebView\WebViewErrorCode::cases())
        === ['load', 'timeout', 'blocked', 'process-gone'],
    'WebViewErrorCode values must match the native error codes.',
);
