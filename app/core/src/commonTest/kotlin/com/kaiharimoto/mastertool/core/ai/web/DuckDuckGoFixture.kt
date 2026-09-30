package com.kaiharimoto.mastertool.core.ai.web

/**
 * DuckDuckGo's HTML endpoint, for `SearchResults`. The live page could not be captured
 * from the build sandbox (2026-09-30): every request, GET or POST, with a browser's user
 * agent or our own, got the bot challenge with HTTP 202 — [CHALLENGE] is that page,
 * trimmed. [RESULTS] is written to DuckDuckGo's known result markup: an ad first
 * (`result--ad`, clicking through `y.js`), then organic results whose links go through
 * the `/l/?uddg=` redirect, `<b>` round the matched words, entities in titles, a
 * snippet as an `<a>` and one as a `<div>`, and a repeated URL.
 */
object DuckDuckGoFixture {
    val RESULTS = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta http-equiv="content-type" content="text/html; charset=UTF-8">
    <title>Snake-Eye Yu-Gi-Oh combo at DuckDuckGo</title>
    <link rel="stylesheet" href="//duckduckgo.com/dist/h.5c1a1d.css" type="text/css">
</head>
<body class="body--html">
<div>
  <div class="header--aside"><a href="/html/" class="header__logo-wrap"></a></div>
  <form id="search_form" name="x" action="/html/" method="post">
    <input type="text" name="q" class="search__input" value="Snake-Eye Yu-Gi-Oh combo" autocomplete="off">
    <input type="submit" class="search__button" value="S">
  </form>
</div>
<div id="links" class="results">
  <div class="result results_links results_links_deep result--ad ">
    <div class="links_main links_deep result__body">
      <h2 class="result__title">
        <a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad_domain=tcgplayer.com&amp;ad_provider=bingv7aa&amp;ad_type=txad&amp;u3=https%3A%2F%2Fwww.bing.com%2Faclick">Buy Snake-Eye Singles - Low Prices, Fast Shipping</a>
      </h2>
      <div class="result__extras">
        <div class="result__extras__url"><a class="result__url" href="https://duckduckgo.com/y.js?ad_domain=tcgplayer.com">tcgplayer.com</a></div>
      </div>
      <a class="result__snippet" href="https://duckduckgo.com/y.js?ad_domain=tcgplayer.com">Shop <b>Snake-Eye</b> cards from thousands of sellers.</a>
      <div class="clear"></div>
    </div>
  </div>

  <div class="result results_links results_links_deep web-result ">
    <div class="links_main links_deep result__body">
      <h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fyugipedia.com%2Fwiki%2FSnake%2DEye&amp;rut=6a4f1f0c2b0d9e1c7d5e0b3a">Snake-Eye - Yugipedia</a>
      </h2>
      <div class="result__extras">
        <div class="result__extras__url">
          <span class="result__icon"><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fyugipedia.com%2Fwiki%2FSnake%2DEye&amp;rut=6a4f"><img class="result__icon__img" width="16" height="16" alt="" src="//external-content.duckduckgo.com/ip3/yugipedia.com.ico" name="i15"></a></span>
          <a class="result__url" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fyugipedia.com%2Fwiki%2FSnake%2DEye&amp;rut=6a4f">yugipedia.com/wiki/Snake-Eye</a>
        </div>
      </div>
      <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fyugipedia.com%2Fwiki%2FSnake%2DEye&amp;rut=6a4f">&quot;<b>Snake</b>-<b>Eye</b>&quot; is a theme of mostly Level 1 FIRE Pyro monsters, focused around placing monsters in the Spell &amp; Trap Zone as Continuous Spell Cards&hellip;</a>
      <div class="clear"></div>
    </div>
  </div>

  <div class="result results_links results_links_deep web-result ">
    <div class="links_main links_deep result__body">
      <h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.reddit.com%2Fr%2Fyugioh%2Fcomments%2F18x2k3j%2Fsnakeeye_combo_guide_for_beginners%2F&amp;rut=0b9d2c">Snake-Eye combo guide for beginners : r/yugioh - Reddit</a>
      </h2>
      <div class="result__extras">
        <div class="result__extras__url"><a class="result__url" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.reddit.com%2Fr%2Fyugioh%2F&amp;rut=0b9d2c">www.reddit.com/r/yugioh/comments/18x2k3j/snakeeye_combo_guide_for_beginners/</a></div>
      </div>
      <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.reddit.com%2Fr%2Fyugioh%2F&amp;rut=0b9d2c">Start with <b>Snake-Eye</b> Ash: search Poplar, Poplar special summons itself &mdash; then Link into Linkuriboh and send both to the GY for Oak.</a>
      <div class="clear"></div>
    </div>
  </div>

  <div class="result results_links results_links_deep web-result ">
    <div class="links_main links_deep result__body">
      <h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.masterduelmeta.com%2Ftier%2Dlist%2Fdeck%2Dtypes%2FSnake%2DEye&amp;rut=77e1aa">Snake-Eye Deck Type &amp; Combos | Master Duel Meta</a>
      </h2>
      <div class="result__extras">
        <div class="result__extras__url"><a class="result__url" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.masterduelmeta.com%2F&amp;rut=77e1aa">www.masterduelmeta.com/tier-list/deck-types/Snake-Eye</a></div>
      </div>
      <div class="result__snippet">Top decklists, combos and the <b>Snake-Eye</b> engine&#39;s best extenders &#x2014; updated daily.</div>
      <div class="clear"></div>
    </div>
  </div>

  <div class="result results_links results_links_deep web-result ">
    <div class="links_main links_deep result__body">
      <h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fyugipedia.com%2Fwiki%2FSnake%2DEye&amp;rut=ffff">Snake-Eye - Yugipedia (again)</a>
      </h2>
      <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fyugipedia.com%2Fwiki%2FSnake%2DEye&amp;rut=ffff">A repeated address.</a>
    </div>
  </div>

  <div class="result results_links results_links_deep web-result ">
    <div class="links_main links_deep result__body">
      <h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fygoprodeck.com%2Fdeck%2Fsnake%2Deye%2Dfiendsmith%2D512345%3Fsort%3Dnew%26page%3D2&amp;rut=1c2d">Snake-Eye Fiendsmith - YGOPRODeck</a>
      </h2>
      <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fygoprodeck.com%2F&amp;rut=1c2d">Deck built for the 2025 format. <b>Combo</b> ends on Flamberge Dragon &amp; Fiendsmith&#8217;s Requiem.</a>
    </div>
  </div>

  <div class="result results_links results_links_deep web-result ">
    <div class="links_main links_deep result__body">
      <h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fja.yugioh%2Dwiki.net%2Findex.php%3F%25E3%2580%258A%25E8%259B%2587%25E7%259C%25BC%25E3%2580%258B&amp;rut=9e0f">《蛇眼》 - 遊戯王 Wiki</a>
      </h2>
      <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fja.yugioh%2Dwiki.net%2F&amp;rut=9e0f">「蛇眼」と名のついたカード群。</a>
    </div>
  </div>

  <div class="nav-link">
    <form action="/html/" method="post">
      <input type="submit" class="btn btn--alt" value="Next">
      <input type="hidden" name="q" value="Snake-Eye Yu-Gi-Oh combo">
      <input type="hidden" name="s" value="10">
    </form>
  </div>
</div>
</body>
</html>
"""

    /** The page every request from the sandbox got instead, trimmed (four of the nine tiles kept). */
    val CHALLENGE = """
<!DOCTYPE html>
<html lang="en">
<head>
    <link rel="canonical" href="https://duckduckgo.com/">
    <meta http-equiv="content-type" content="text/html; charset=UTF-8">
    <title>
        DuckDuckGo
    </title>
    <link rel="stylesheet" media="handheld, all" href="//duckduckgo.com/dist/l.304bf63bbd053ee46b28.css" type="text/css"/>
</head>
<body>
    <a name="top"></a>
    <center id="lite_wrapper">
        <br><a class="header-url" href="/html/"><span class="header">DuckDuckGo</span></a><br><br>
        <iframe name="ifr" width="0" height="0" border="0" class="hidden"></iframe>
        <form id="challenge-form" action="//duckduckgo.com/anomaly.js?sv=html&cc=botnet&st=1790752157&gk=d4cd0dabcf4caa22ad92fab40844c786&q=Snake-Eye Yu-Gi-Oh combo&r=use" method="POST">
            <div class="anomaly-modal__mask">
                <div class="anomaly-modal__modal  is-ie" data-testid="anomaly-modal">
                    <div class="anomaly-modal__title">Unfortunately, bots use DuckDuckGo too.</div>
                    <div class="anomaly-modal__description">Please complete the following challenge to confirm this search was made by a human.</div>
                    <div class="anomaly-modal__instructions">Select all squares containing a duck:</div>
                    <div class="anomaly-modal__puzzle-margins">
                        <div class="anomaly-modal__puzzle">
                            <div class="anomaly-modal__box" data-index="0">
                                <label class="" for="image-check_6fd4783e3acc47ee60d1c662989038af" data-testid="anomaly-modal-tile-0">
                                    <input type="checkbox" class="anomaly-modal__check" name="image-check_6fd4783e3acc47ee60d1c662989038af" id="image-check_6fd4783e3acc47ee60d1c662989038af">
                                    <img class="anomaly-modal__image" alt=" " src="../assets/anomaly/images/challenge/6fd4783e3acc47ee60d1c662989038af.jpg">
                                </label>
                            </div>
                            <div class="anomaly-modal__box" data-index="1">
                                <label class="" for="image-check_5194e2cbb422bc09bf79d439a9ca361b" data-testid="anomaly-modal-tile-1">
                                    <input type="checkbox" class="anomaly-modal__check" name="image-check_5194e2cbb422bc09bf79d439a9ca361b" id="image-check_5194e2cbb422bc09bf79d439a9ca361b">
                                    <img class="anomaly-modal__image" alt=" " src="../assets/anomaly/images/challenge/5194e2cbb422bc09bf79d439a9ca361b.jpg">
                                </label>
                            </div>
                            <div class="anomaly-modal__box" data-index="2">
                                <label class="" for="image-check_defd928683764b90d9f13ecc64b99102" data-testid="anomaly-modal-tile-2">
                                    <input type="checkbox" class="anomaly-modal__check" name="image-check_defd928683764b90d9f13ecc64b99102" id="image-check_defd928683764b90d9f13ecc64b99102">
                                    <img class="anomaly-modal__image" alt=" " src="../assets/anomaly/images/challenge/defd928683764b90d9f13ecc64b99102.jpg">
                                </label>
                            </div>
                            <div class="anomaly-modal__box" data-index="3">
                                <label class="" for="image-check_18bfa8e9e3c3c78368b8fe614b558c85" data-testid="anomaly-modal-tile-3">
                                    <input type="checkbox" class="anomaly-modal__check" name="image-check_18bfa8e9e3c3c78368b8fe614b558c85" id="image-check_18bfa8e9e3c3c78368b8fe614b558c85">
                                    <img class="anomaly-modal__image" alt=" " src="../assets/anomaly/images/challenge/18bfa8e9e3c3c78368b8fe614b558c85.jpg">
                                </label>
                            </div>
                        </div>
                    </div>
                    <button class="anomaly-modal__submit" type="submit" disabled>Submit</button>
                </div>
            </div>
        </form>
    </center>
</body>
</html>
"""
}
