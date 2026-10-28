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

