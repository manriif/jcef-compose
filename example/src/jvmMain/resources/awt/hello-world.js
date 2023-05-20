/* https://codepen.io/vickimurley/pen/LYXRwo */

function init() {
    var letters = document.querySelectorAll('.hello>.letter');
    var delay = 3;
    var duration = 0.8;
    for (var i = 0; i < letters.length; i++) {
        letters[i].style.webkitTransitionDelay = delay + "s";
        letters[i].style.webkitTransitionDuration = duration + "s";

        delay -= 0.5;
        duration += 0.2;
    }
    startTransitions();
}

function startTransitions() {
    var letters = document.querySelectorAll('.letter');
    for (var i = 0; i < letters.length; i++) {
        letters[i].classList.remove('offscreen');
    }
}

function resetTransitions() {
    var letters = document.querySelectorAll('.letter');
    for (var i = 0; i < letters.length; i++) {
        letters[i].classList.add('offscreen');
    }
    document.querySelector('.world').classList.add('offscreen');
}

function checkLetter() {
    var letter = event.target;
    console.log(letter.innerText);
    if (!letter.classList.contains('first')) return;

    document.querySelector('.world.offscreen').classList.remove('offscreen');
}